package com.github.mmooyyii.malguem;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Inflater;

// zip 包的流式随机读: 全程只按字节区间取, 从不下载整包.
// 开包读一次文件尾拿到 EOCD + 中央目录, 之后取条目按 [本地头+压缩数据] 的精确区间,
// 一批条目合成一次 multi-range 请求 (1 次往返), 解压后进带容量上限的 LRU 缓存.
// epub (LazyEpub) 和 cbz (LazyCbz) 共用这一层, 差别只在"包里的东西怎么解释"
public abstract class LazyZip {
    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CENTRAL_DIR_SIGNATURE = 0x02014b50;
    private static final int LOCAL_HEADER_SIGNATURE = 0x04034b50;
    private static final int TAIL_SIZE = 512 * 1024; // 首次读取的尾部大小, 尽量一次拿到 EOCD + 整个中央目录
    private static final int HEADER_SLACK = 512;      // 单文件一次读回时本地头长度的冗余上界

    final String uri;
    final ResourceInterface file;

    final ConcurrentHashMap<String, CentralDirEntry> zip_dir = new ConcurrentHashMap<>(); // name -> (offset,size)
    // 全部条目本地头偏移的有序数组: 相邻条目的偏移差就是该条目 [本地头+数据] 的精确长度, 批量读时无需先取头再取数据
    private long[] sortedOffsets = new long[0];
    private int eocdIdxInTail; // EOCD 签名在 tail 缓冲中的下标

    Integer centralDirOffset;
    Integer centralDirSize;

    // name -> 解压后的字节, 带容量上限的 LRU, 防止长时间阅读时无限增长导致 OOM.
    // 上限取 256MB 与 堆上限一半 的较小值: 大预取窗口需要更大的缓存, 但低内存盒子不能被撑爆
    private final LinkedHashMap<String, byte[]> resource = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheBytes = 0;
    private static final long MAX_CACHE_BYTES =
            Math.min(256L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 2);

    protected LazyZip(String uri, ResourceInterface file) {
        this.uri = uri;
        this.file = file;
    }

    // ---- 开包 ----

    protected void readCentralDirectory() throws Exception {
        // 一次读取较大的文件尾部, 通常同时包含 EOCD 和整个中央目录 (也覆盖 ZIP 注释), 省掉一轮往返
        var slice = new Slice();
        slice.offset = -TAIL_SIZE;
        byte[] tail = file.open(uri, slice);
        if (!locateCentralDir(tail)) {
            throw new IllegalArgumentException("EOCD signature not found");
        }
        // 中央目录紧邻 EOCD 之前, 若已落在 tail 内(校验签名)则直接解析, 否则再单独请求
        int cdStart = eocdIdxInTail - centralDirSize;
        boolean cdInTail = cdStart >= 0
                && ByteBuffer.wrap(tail, cdStart, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == CENTRAL_DIR_SIGNATURE;
        if (cdInTail) {
            parseCentralDirectory(Arrays.copyOfRange(tail, cdStart, eocdIdxInTail));
        } else {
            var s = new Slice();
            s.offset = centralDirOffset;
            s.size = centralDirSize;
            parseCentralDirectory(file.open(uri, s));
        }
        buildSortedOffsets();
        // 不预取所有条目的本地头, 改为按需在 load 时惰性获取
    }

    private boolean locateCentralDir(byte[] endBytes) {
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
                eocdIdxInTail = i;
                return true;
            }
        }
        return false;
    }

    private void parseCentralDirectory(byte[] centralDirData) {
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
            registerEntry(entry);
            onCentralDirEntry(entry);
            // 计算下一个条目的起始位置
            position += 46 + fileNameLength + extraFieldLength + fileCommentLength;
        }
    }

    // 解析中央目录时逐条回调, 子类用来挑自己关心的条目 (epub 找 .opf, cbz 收图片)
    protected void onCentralDirEntry(CentralDirEntry entry) {
    }

    // 以完整文件名及其每级子路径为 key 注册条目 (页面里的相对引用常用子路径)
    protected void registerEntry(CentralDirEntry entry) {
        var path = Paths.get(entry.fileName);
        for (int i = 0; i < path.getNameCount(); i++) {
            var subPath = path.subpath(i, path.getNameCount()).toString();
            if (!zip_dir.containsKey(subPath)) {
                zip_dir.put(subPath, entry);
            }
        }
        zip_dir.put(entry.fileName, entry);
    }

    protected void buildSortedOffsets() {
        var set = new TreeSet<Long>();
        for (var e : zip_dir.values()) {
            set.add(e.localHeaderOffset);
        }
        sortedOffsets = new long[set.size()];
        int i = 0;
        for (var v : set) {
            sortedOffsets[i++] = v;
        }
    }

    // ---- 缓存 ----

    protected synchronized boolean cacheHas(String name) {
        return resource.containsKey(name);
    }

    protected synchronized byte[] cacheGet(String name) {
        return resource.get(name);
    }

    protected synchronized void cachePut(String name, byte[] bytes) {
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

    // ---- 取内容 ----

    public byte[] load_file(String filename) throws Exception {
        filename = cut(filename);
        var cached = cacheGet(filename);
        if (cached != null) {
            return cached;
        }
        // 单文件路径保留"找不到就报错"的语义 (批量预取里则是跳过)
        if (!zip_dir.containsKey(filename)) {
            throw new IllegalArgumentException("no such file: " + filename);
        }
        var tmp = new ArrayList<String>();
        tmp.add(filename);
        load_file_to_cache(tmp);
        return cacheGet(filename);
    }

    protected void load_file_to_cache(List<String> filenames) throws Exception {
        var needed = new ArrayList<String>();
        for (var filename : filenames) {
            filename = cut(filename);
            if (cacheHas(filename)) {
                continue;
            }
            if (!zip_dir.containsKey(filename)) {
                // 引用的文件不在包内(坏引用/未收录), 跳过即可, 不能让一个坏引用拖垮整批预取
                continue;
            }
            var entry = zip_dir.get(filename);
            assert entry != null;
            // 空文件没有压缩数据, 直接缓存空字节; 否则 size=0 会生成非法反向 Range
            if (entry.compressedSize == 0) {
                cachePut(filename, new byte[0]);
                continue;
            }
            needed.add(filename);
        }
        if (needed.isEmpty()) {
            return;
        }
        onePhase(needed);
    }

    // 条目 [本地头+数据] 的精确长度: 相邻条目的偏移差 (含数据描述符); 兜底用文件名长度+冗余估算.
    // 取两者较小值, 既不越入下一条目 (multi-range 不出现重叠区间), 也不为大间隙多拉数据
    private long spanOf(CentralDirEntry entry) {
        long slackSpan = 30L + entry.fileName.getBytes(StandardCharsets.UTF_8).length
                + HEADER_SLACK + entry.compressedSize;
        int idx = Arrays.binarySearch(sortedOffsets, entry.localHeaderOffset);
        long next = -1;
        if (idx >= 0 && idx + 1 < sortedOffsets.length) {
            next = sortedOffsets[idx + 1];
        } else if (idx >= 0 && centralDirOffset != null && centralDirOffset > entry.localHeaderOffset) {
            next = centralDirOffset;
        }
        if (next > entry.localHeaderOffset) {
            return Math.min(next - entry.localHeaderOffset, slackSpan);
        }
        return slackSpan;
    }

    // 单相批量加载: 每个条目按精确区间一次取回 [本地头+压缩数据], 全部合成一次 multi-range 请求 (1 次往返).
    // 个别条目区间不足(超大扩展字段等)时回退两段式
    private void onePhase(List<String> filenames) throws Exception {
        var slices = new ArrayList<Slice>();
        var sliceToFile = new HashMap<Slice, String>();
        for (var filename : filenames) {
            var entry = zip_dir.get(filename);
            if (entry == null) {
                continue;
            }
            var s = new Slice();
            s.offset = Math.toIntExact(entry.localHeaderOffset);
            s.size = Math.toIntExact(spanOf(entry));
            slices.add(s);
            sliceToFile.put(s, filename);
        }
        if (slices.isEmpty()) {
            return;
        }
        var raws = file.open(uri, slices);
        var fallback = new ArrayList<String>();
        for (var s : slices) {
            var filename = sliceToFile.get(s);
            if (filename == null) {
                continue;
            }
            var entry = zip_dir.get(filename);
            var raw = raws.get(s);
            if (entry == null || raw == null) {
                fallback.add(filename);
                continue;
            }
            try {
                int dataStart = Math.toIntExact(parseDataOffset(raw));
                int size = Math.toIntExact(entry.compressedSize);
                if (raw.length < dataStart + size) {
                    fallback.add(filename);
                    continue;
                }
                store(filename, entry, Arrays.copyOfRange(raw, dataStart, dataStart + size));
            } catch (Exception e) {
                fallback.add(filename);
            }
        }
        if (!fallback.isEmpty()) {
            twoPhase(fallback);
        }
    }

    // 多文件惰性加载: 阶段1批量取本地头(小区间)定位偏移, 阶段2批量取精确压缩数据
    private void twoPhase(List<String> filenames) throws Exception {
        var headSlices = new ArrayList<Slice>();
        var headToFile = new HashMap<Slice, String>();
        for (var filename : filenames) {
            var entry = zip_dir.get(filename);
            if (entry == null) {
                continue;
            }
            var s = new Slice();
            s.offset = Math.toIntExact(entry.localHeaderOffset);
            s.size = 48; // 只需读到本地头的文件名/扩展字段长度字段
            headSlices.add(s);
            headToFile.put(s, filename);
        }
        var heads = file.open(uri, headSlices);
        var dataSlices = new ArrayList<Slice>();
        var dataToFile = new HashMap<Slice, String>();
        for (var s : headSlices) {
            var head = heads.get(s);
            var filename = headToFile.get(s);
            if (head == null || filename == null) {
                continue;
            }
            var entry = zip_dir.get(filename);
            if (entry == null) {
                continue;
            }
            var d = new Slice();
            d.offset = Math.toIntExact(entry.localHeaderOffset + parseDataOffset(head));
            d.size = Math.toIntExact(entry.compressedSize);
            dataSlices.add(d);
            dataToFile.put(d, filename);
        }
        var datas = file.open(uri, dataSlices);
        for (var s : dataSlices) {
            var data = datas.get(s);
            var filename = dataToFile.get(s);
            if (data == null || filename == null) {
                continue;
            }
            store(filename, zip_dir.get(filename), data);
        }
    }

    private void store(String filename, CentralDirEntry entry, byte[] compressed) throws Exception {
        if (entry.compressionMethod == 0) {
            cachePut(filename, compressed);
        } else if (entry.compressionMethod == 8) {
            Inflater inflater = new Inflater(true);
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            try {
                while (!inflater.finished()) {
                    int n = inflater.inflate(buffer);
                    if (n == 0) {
                        break; // needsInput/needsDictionary, 避免死循环
                    }
                    out.write(buffer, 0, n);
                }
            } finally {
                inflater.end();
            }
            cachePut(filename, out.toByteArray());
        } else {
            throw new IllegalArgumentException("无法解压: " + entry.compressionMethod);
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

    protected static String cut(String filename) {
        while (filename.startsWith(".") || filename.startsWith("/")) {
            filename = filename.substring(1);
        }
        return filename;
    }

    // 按扩展名猜 MIME: manifest 没覆盖到(或包里根本没有 manifest)时的兜底, CSS/字体对 MIME 敏感
    protected static String mediaTypeByExt(String filename) {
        var lower = filename.toLowerCase();
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "text/javascript";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".avif")) return "image/avif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".xhtml") || lower.endsWith(".html")) return "application/xhtml+xml";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".otf")) return "font/otf";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return null;
    }

    public static class CentralDirEntry {
        public final String fileName;
        public final long compressedSize;
        public final long uncompressedSize;
        public final long localHeaderOffset;
        public final int compressionMethod;
        public final int extraFieldLength;

        public CentralDirEntry(String fileName, long compressedSize, long uncompressedSize, long localHeaderOffset, int compressionMethod, int extraFieldLength) {
            this.fileName = fileName;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.localHeaderOffset = localHeaderOffset;
            this.compressionMethod = compressionMethod;
            this.extraFieldLength = extraFieldLength;
        }
    }
}
