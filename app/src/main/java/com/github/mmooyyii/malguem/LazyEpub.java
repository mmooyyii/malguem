package com.github.mmooyyii.malguem;

import org.jsoup.Jsoup;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Inflater;

import javax.xml.parsers.DocumentBuilderFactory;

public class LazyEpub implements Book {
    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CENTRAL_DIR_SIGNATURE = 0x02014b50;
    private static final int LOCAL_HEADER_SIGNATURE = 0x04034b50;

    String title;
    String uri;
    ResourceInterface file;
    // resource map
    List<String> contents; // page -> html

    ConcurrentHashMap<String, byte[]> resource; // name -> html
    ConcurrentHashMap<String, String> resource_type; // name -> media_type name
    ConcurrentHashMap<String, CentralDirEntry> zip_dir; // page -> (offset,size)


    Integer centralDirOffset;
    Integer centralDirSize;

    String opf_file;

    public LazyEpub(String epub_uri, ResourceInterface client) throws Exception {
        uri = epub_uri;
        file = client;
        contents = new ArrayList<>();
        resource = new ConcurrentHashMap<>();
        zip_dir = new ConcurrentHashMap<>();
        resource_type = new ConcurrentHashMap<>();
        init_epub_dir();
    }

    private void init_epub_dir() throws Exception {
        // 请求ZIP文件末尾的22字节（End of Central Directory最小长度）
        var slice = new Slice();
        slice.offset = -22;
        initCentralDirLocate(file.open(uri, slice));
        slice.offset = centralDirOffset;
        slice.size = centralDirSize;
        initCentralDirectory(file.open(uri, slice));
        initCompressedOffset();
        initContent();
    }

    public String page(int page_num) {
        var filename = contents.get(page_num);
        filename = cut(filename);
        if (resource.containsKey(filename)) {
            var html = resource.get(filename);
            return new String(html, StandardCharsets.UTF_8);
        }
        return "无法读取html";
    }

    @Override
    public void prepare(int from, int to) {
        var files = new ArrayList<String>();
        for (var page_num = from; page_num < to; ++page_num) {
            try {
                var filename = contents.get(page_num);
                var html = new String(load_file(filename), StandardCharsets.UTF_8);
                var doc = Jsoup.parse(html);
                // 这里怎么才能一次性写好呢?
                for (var script : doc.select("script[src]")) {
                    files.add(script.attr("src"));
                }
                for (var link : doc.select("link[href]")) {
                    files.add(link.attr("href"));
                }
                for (var img : doc.select("img[src]")) {
                    files.add(img.attr("src"));
                }
                for (var image : doc.select("image[xlink:href]")) {
                    files.add(image.attr("xlink:href"));
                }
            } catch (Exception ignore) {
            }
        }
        try {
            load_file_to_cache(files);
        } catch (Exception ignore) {
        }
    }

    public int total_pages() {
        return contents.size();
    }

    @Override
    public byte[] GetResource(String filename) throws Exception {
        filename = cut(filename);
        if (resource.containsKey(filename)) {
            return resource.get(filename);
        }
        throw new Exception("找不到对应文件" + filename);
    }

    @Override
    public String GetMediaType(String filename) {
        filename = cut(filename);
        return resource_type.get(filename);
    }

    private void initCentralDirLocate(byte[] endBytes) {
        // 检查最小长度（End of Central Directory的最小长度为22字节）
        if (endBytes == null || endBytes.length < 22) {
            throw new IllegalArgumentException("Invalid EOCD data: length < 22 bytes");
        }
        // 从后往前搜索EOCD签名（处理ZIP注释可能存在的干扰）
        for (int i = endBytes.length - 22; i >= 0; i--) {
            // 将4字节转换为int（小端序转换）
            int signature = ByteBuffer.wrap(endBytes, i, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (signature == EOCD_SIGNATURE) {
                centralDirSize = ByteBuffer.wrap(endBytes, i + 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                centralDirOffset = ByteBuffer.wrap(endBytes, i + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                return;
            }
        }
        throw new IllegalArgumentException("EOCD signature not found");
    }

    private void initCentralDirectory(byte[] centralDirData) {
        int position = 0;
        while (position < centralDirData.length) {
            // 校验中央目录条目签名
            int signature = ByteBuffer.wrap(centralDirData, position, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (signature != CENTRAL_DIR_SIGNATURE) {
                break;
            }
            int compressionMethod = ByteBuffer.wrap(centralDirData, position + 10, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
            long compressedSize = ByteBuffer.wrap(centralDirData, position + 20, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            long uncompressedSize = ByteBuffer.wrap(centralDirData, position + 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            // 解析文件名长度和扩展字段长度
            int fileNameLength = ByteBuffer.wrap(centralDirData, position + 28, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
            int extraFieldLength = ByteBuffer.wrap(centralDirData, position + 30, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
            int fileCommentLength = ByteBuffer.wrap(centralDirData, position + 32, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
            // 解析本地文件头偏移量（关键字段）
            long localHeaderOffset = ByteBuffer.wrap(centralDirData, position + 42, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            // 提取文件名（UTF-8编码处理）
            String fileName = new String(centralDirData, position + 46, fileNameLength);
            // 构建条目对象
            CentralDirEntry entry = new CentralDirEntry(fileName, compressedSize, uncompressedSize, localHeaderOffset, compressionMethod, extraFieldLength);
            Path path = Paths.get(fileName);
            // 遍历路径的每一部分
            for (int i = 0; i < path.getNameCount(); i++) {
                // 获取从第 i 部分到末尾的子路径
                var subPath = path.subpath(i, path.getNameCount()).toString();
                if (!zip_dir.containsKey(subPath)) {
                    zip_dir.put(subPath, entry);
                }
            }
            zip_dir.put(fileName, entry);
            if (fileName.endsWith(".opf")) {
                // 正确做法应该是去META-INF/container.xml里找, 这样做应该也行
                opf_file = fileName;
            }
            // 计算下一个条目的起始位置
            position += 46 + fileNameLength + extraFieldLength + fileCommentLength;
        }
    }


    public void initContent() throws Exception {
        // 用.opf来解析全书结构
        if (opf_file == null) {
            throw new IllegalArgumentException("无法解析epub");
        }
        var opf = load_file(opf_file);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(opf));

        doc.getDocumentElement().normalize();
        //  解析元数据
        NodeList titles = doc.getElementsByTagName("dc:title");
        title = titles.item(0).getTextContent();
        // 解析资源清单
        NodeList items = doc.getElementsByTagName("item");
        var id_to_path = new HashMap<String, String>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String id = item.getAttribute("id");
            String href = item.getAttribute("href");
            String mediaType = item.getAttribute("media-type");
            resource_type.put(href, mediaType);
            id_to_path.put(id, href);
        }
        // 解析阅读顺序
        NodeList spineItems = doc.getElementsByTagName("itemref");
        for (int i = 0; i < spineItems.getLength(); i++) {
            String idref = spineItems.item(i).getAttributes().getNamedItem("idref").getNodeValue();
            contents.add(id_to_path.get(idref));
        }
    }

    private void initCompressedOffset() throws Exception {
        var slices = new ArrayList<Slice>();
        var slice_to_key = new HashMap<Slice, String>();
        for (var kv : zip_dir.entrySet()) {
            var slice = new Slice();
            slice.size = 64;
            var entry = kv.getValue();
            slice.offset = Math.toIntExact(entry.localHeaderOffset);
            slices.add(slice);
            slice_to_key.put(slice, kv.getKey());
        }
        var files = file.open(uri, slices);
        for (var idx = 0; idx < slices.size(); ++idx) {
            var slice = slices.get(idx);
            var file = files.get(slice);
            var key = slice_to_key.get(slice);
            assert key != null;
            var entry = zip_dir.get(key);
            assert entry != null;
            long dataOffset = entry.localHeaderOffset + parseDataOffset(file);
            entry.SetCompressedOffset((int) dataOffset);
        }
    }


    private String cut(String filename) {
        while (filename.startsWith(".") || filename.startsWith("/")) {
            filename = filename.substring(1);
        }
        return filename;
    }


    public byte[] load_file(String filename) throws Exception {
        filename = cut(filename);
        if (resource.containsKey(filename)) {
            return resource.get(filename);
        }
        var tmp = new ArrayList<String>();
        tmp.add(filename);
        load_file_to_cache(tmp);
        return resource.get(filename);
    }

    private void load_file_to_cache(List<String> filenames) throws Exception {
        var slices = new ArrayList<Slice>();
        var slicesToFile = new HashMap<Slice, String>();
        for (var filename : filenames) {
            filename = cut(filename);
            if (resource.containsKey(filename)) {
                continue;
            }
            if (!zip_dir.containsKey(filename)) {
                throw new IllegalArgumentException("no such file");
            }
            var entry = zip_dir.get(filename);
            var slice = new Slice();
            assert entry != null;
            long dataOffset = entry.compressedOffset;
            slice.offset = Math.toIntExact(dataOffset);
            slice.size = Math.toIntExact(entry.compressedSize);
            slices.add(slice);
            slicesToFile.put(slice, filename);
        }
        if (slices.isEmpty()) {
            return;
        }
        var sliceToBytes = file.open(uri, slices);
        for (var kv : sliceToBytes.entrySet()) {
            var slice = kv.getKey();
            var bytes = kv.getValue();
            var filename = slicesToFile.get(slice);
            assert filename != null;
            var entry = zip_dir.get(filename);
            assert entry != null;
            if (entry.compressionMethod == 0) {
                resource.put(filename, bytes);
            } else if (entry.compressionMethod == 8) {
                Inflater decompresser = new Inflater(true);
                decompresser.setInput(bytes);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                try {
                    while (!decompresser.finished()) {
                        int count = decompresser.inflate(buffer); // 解压数据块
                        outputStream.write(buffer, 0, count);
                    }
                } finally {
                    decompresser.end(); // 必须手动释放资源
                }
                var output = outputStream.toByteArray();
                resource.put(filename, output);
            } else {
                throw new IllegalArgumentException("无法解压epub: " + entry.compressionMethod);
            }
        }
    }

    public static long parseDataOffset(byte[] localHeaderData) {
        // 校验最小长度（至少需要38字节）
        if (localHeaderData == null || localHeaderData.length < 38) {
            throw new IllegalArgumentException("本地文件头数据不完整");
        }
        // 校验签名
        int signature = ByteBuffer.wrap(localHeaderData, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (signature != LOCAL_HEADER_SIGNATURE) {
            throw new IllegalArgumentException("无效的本地文件头签名");
        }
        // 解析文件名和扩展字段长度
        int fileNameLength = ByteBuffer.wrap(localHeaderData, 26, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
        int extraFieldLength = ByteBuffer.wrap(localHeaderData, 28, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
        // 计算数据偏移量：本地头长度(30) + 文件名长度 + 扩展字段长度
        return 30L + fileNameLength + extraFieldLength;
    }

    public static class CentralDirEntry {
        public final String fileName;
        public final long compressedSize;
        public final long uncompressedSize;
        public final long localHeaderOffset;
        public final int compressionMethod;
        public final int extraFieldLength;
        public int compressedOffset;

        public CentralDirEntry(String fileName, long compressedSize, long uncompressedSize, long localHeaderOffset, int compressionMethod, int extraFieldLength) {
            this.fileName = fileName;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.localHeaderOffset = localHeaderOffset;
            this.compressionMethod = compressionMethod;
            this.extraFieldLength = extraFieldLength;
        }

        public void SetCompressedOffset(int compressedOffset) {
            this.compressedOffset = compressedOffset;
        }
    }
}
