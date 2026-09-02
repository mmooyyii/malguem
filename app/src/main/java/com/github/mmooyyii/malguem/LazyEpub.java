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
import java.util.LinkedHashMap;
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

    // name -> 解压后的字节, 带容量上限的 LRU, 防止长时间阅读时无限增长导致 OOM
    private final LinkedHashMap<String, byte[]> resource = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheBytes = 0;
    private static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;

    ConcurrentHashMap<String, String> resource_type; // name -> media_type name
    ConcurrentHashMap<String, CentralDirEntry> zip_dir; // page -> (offset,size)


    Integer centralDirOffset;
    Integer centralDirSize;

    String opf_file;
    String cover_href; // 封面图片在 epub 内的路径, 没有则为 null

    public LazyEpub(String epub_uri, ResourceInterface client) throws Exception {
        uri = epub_uri;
        file = client;
        contents = new ArrayList<>();
        zip_dir = new ConcurrentHashMap<>();
        resource_type = new ConcurrentHashMap<>();
        init_epub_dir();
    }

    private void init_epub_dir() throws Exception {
        // 先请求ZIP文件末尾的22字节（End of Central Directory最小长度）
        var slice = new Slice();
        slice.offset = -22;
        if (!initCentralDirLocate(file.open(uri, slice))) {
            // ZIP 可能带注释, EOCD 不在最后 22 字节内, 扩大后缀范围重试 (22 + 最大注释长度 65535)
            slice = new Slice();
            slice.offset = -65557;
            if (!initCentralDirLocate(file.open(uri, slice))) {
                throw new IllegalArgumentException("EOCD signature not found");
            }
        }
        slice = new Slice();
        slice.offset = centralDirOffset;
        slice.size = centralDirSize;
        initCentralDirectory(file.open(uri, slice));
        initCompressedOffset();
        initContent();
    }

    private synchronized boolean cacheHas(String name) {
        return resource.containsKey(name);
    }

    private synchronized byte[] cacheGet(String name) {
        return resource.get(name);
    }

    private synchronized void cachePut(String name, byte[] bytes) {
        var old = resource.put(name, bytes);
        if (old != null) {
            cacheBytes -= old.length;
        }
        cacheBytes += bytes.length;
        // 超出预算时按访问顺序淘汰最久未使用的条目 (LinkedHashMap accessOrder=true, 迭代器从最旧开始)
        var it = resource.entrySet().iterator();
        while (cacheBytes > MAX_CACHE_BYTES && resource.size() > 1 && it.hasNext()) {
            var eldest = it.next();
            cacheBytes -= eldest.getValue().length;
            it.remove();
        }
    }

    public String page(int page_num) {
        var filename = contents.get(page_num);
        filename = cut(filename);
        var html = cacheGet(filename);
        if (html != null) {
            return new String(html, StandardCharsets.UTF_8);
        }
        return "无法读取html";
    }

    @Override
    public void prepare(int from, int to) {
        var files = new ArrayList<String>();
        for (var page_num = from; page_num < to; ++page_num) {
            var filename = contents.get(page_num);
            files.add(filename);
        }
        try {
            load_file_to_cache(files);
        } catch (Exception ignore) {
        }
        files.clear();
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

    // 读取封面图片字节, 没有封面返回 null
    public byte[] cover() throws Exception {
        if (cover_href == null) {
            return null;
        }
        return load_file(cover_href);
    }

    @Override
    public byte[] GetResource(String filename) throws Exception {
        filename = cut(filename);
        var bytes = cacheGet(filename);
        if (bytes != null) {
            return bytes;
        }
        throw new Exception("找不到对应文件" + filename);
    }

    @Override
    public String GetMediaType(String filename) {
        filename = cut(filename);
        return resource_type.get(filename);
    }

    private boolean initCentralDirLocate(byte[] endBytes) {
        // 检查最小长度（End of Central Directory的最小长度为22字节）
        if (endBytes == null || endBytes.length < 22) {
            return false;
        }
        // 从后往前搜索EOCD签名（处理ZIP注释可能存在的干扰）
        for (int i = endBytes.length - 22; i >= 0; i--) {
            // 将4字节转换为int（小端序转换）
            int signature = ByteBuffer.wrap(endBytes, i, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (signature == EOCD_SIGNATURE) {
                centralDirSize = ByteBuffer.wrap(endBytes, i + 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                centralDirOffset = ByteBuffer.wrap(endBytes, i + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                return true;
            }
        }
        return false;
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
        if (titles.getLength() > 0 && titles.item(0) != null) {
            title = titles.item(0).getTextContent();
        }
        // 解析资源清单
        NodeList items = doc.getElementsByTagName("item");
        var id_to_path = new HashMap<String, String>();
        String coverByProps = null; // EPUB3: properties="cover-image"
        String coverByName = null;  // 兜底: id/href 含 cover 的图片
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String id = item.getAttribute("id");
            String href = item.getAttribute("href");
            String mediaType = item.getAttribute("media-type");
            // 用 cut(href) 作 key, 与 GetMediaType 的查询方式保持一致, 避免路径归一化不一致导致 MIME 为 null
            resource_type.put(cut(href), mediaType);
            id_to_path.put(id, href);
            var props = item.getAttribute("properties");
            var isImage = mediaType != null && mediaType.startsWith("image");
            if (props != null && props.contains("cover-image")) {
                coverByProps = href;
            }
            if (coverByName == null && isImage
                    && (id.toLowerCase().contains("cover") || href.toLowerCase().contains("cover"))) {
                coverByName = href;
            }
        }
        // EPUB2: <meta name="cover" content="封面item的id">
        String coverById = null;
        NodeList metas = doc.getElementsByTagName("meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element m = (Element) metas.item(i);
            if ("cover".equals(m.getAttribute("name"))) {
                coverById = id_to_path.get(m.getAttribute("content"));
                break;
            }
        }
        if (coverByProps != null) {
            cover_href = coverByProps;
        } else if (coverById != null) {
            cover_href = coverById;
        } else {
            cover_href = coverByName;
        }
        // 解析阅读顺序
        NodeList spineItems = doc.getElementsByTagName("itemref");
        for (int i = 0; i < spineItems.getLength(); i++) {
            var idref_node = spineItems.item(i).getAttributes().getNamedItem("idref");
            if (idref_node == null) {
                continue;
            }
            var path = id_to_path.get(idref_node.getNodeValue());
            // idref 在 manifest 里找不到时会是 null, 不要塞进 contents, 否则翻到该页会 cut(null) NPE
            if (path != null) {
                contents.add(path);
            }
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
            var data = files.get(slice);
            var key = slice_to_key.get(slice);
            // assert 在 Android 上默认关闭, 这里用显式判空兜底, 缺失的 range 跳过而不是 NPE
            if (data == null || key == null) {
                continue;
            }
            var entry = zip_dir.get(key);
            if (entry == null) {
                continue;
            }
            long dataOffset = entry.localHeaderOffset + parseDataOffset(data);
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
        var cached = cacheGet(filename);
        if (cached != null) {
            return cached;
        }
        var tmp = new ArrayList<String>();
        tmp.add(filename);
        load_file_to_cache(tmp);
        return cacheGet(filename);
    }

    private void load_file_to_cache(List<String> filenames) throws Exception {
        var slices = new ArrayList<Slice>();
        var slicesToFile = new HashMap<Slice, String>();
        for (var filename : filenames) {
            filename = cut(filename);
            if (cacheHas(filename)) {
                continue;
            }
            if (!zip_dir.containsKey(filename)) {
                throw new IllegalArgumentException("no such file");
            }
            var entry = zip_dir.get(filename);
            assert entry != null;
            // 空文件没有压缩数据, 直接缓存空字节; 否则 size=0 会生成 "offset-(offset-1)" 的非法反向 Range
            if (entry.compressedSize == 0) {
                cachePut(filename, new byte[0]);
                continue;
            }
            var slice = new Slice();
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
            if (filename == null) {
                continue;
            }
            var entry = zip_dir.get(filename);
            if (entry == null) {
                continue;
            }
            if (entry.compressionMethod == 0) {
                cachePut(filename, bytes);
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
                cachePut(filename, output);
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
