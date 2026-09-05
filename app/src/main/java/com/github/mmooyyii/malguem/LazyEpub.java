package com.github.mmooyyii.malguem;

import com.google.gson.Gson;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Inflater;

import javax.xml.parsers.DocumentBuilderFactory;

public class LazyEpub implements Book {
    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CENTRAL_DIR_SIGNATURE = 0x02014b50;
    private static final int LOCAL_HEADER_SIGNATURE = 0x04034b50;
    private static final int TAIL_SIZE = 512 * 1024; // 首次读取的尾部大小, 尽量一次拿到 EOCD + 整个中央目录
    private static final int HEADER_SLACK = 512;      // 单文件一次读回时本地头长度的冗余上界

    private int eocdIdxInTail; // EOCD 签名在 tail 缓冲中的下标

    String title;
    String uri;
    ResourceInterface file;
    // resource map
    List<String> contents; // page -> html
    List<TocEntry> toc = new ArrayList<>(); // 目录 (标题 -> spine 页码), 没有目录时为空

    // name -> 解压后的字节, 带容量上限的 LRU, 防止长时间阅读时无限增长导致 OOM
    private final LinkedHashMap<String, byte[]> resource = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheBytes = 0;
    private static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;

    ConcurrentHashMap<String, String> resource_type; // name -> media_type name
    ConcurrentHashMap<String, CentralDirEntry> zip_dir; // page -> (offset,size)

    // 全部条目本地头偏移的有序数组: 相邻条目的偏移差就是该条目 [本地头+数据] 的精确长度, 批量读时无需先取头再取数据
    private long[] sortedOffsets = new long[0];


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

    // 从持久化索引恢复, 不发任何网络请求; 索引格式不兼容时抛异常, 由调用方删除索引后走网络重建
    public LazyEpub(String epub_uri, ResourceInterface client, String index_json) {
        uri = epub_uri;
        file = client;
        contents = new ArrayList<>();
        zip_dir = new ConcurrentHashMap<>();
        resource_type = new ConcurrentHashMap<>();
        var data = new Gson().fromJson(index_json, IndexData.class);
        if (data == null || data.v != INDEX_VERSION || data.entries == null || data.spine == null) {
            throw new IllegalArgumentException("索引格式不兼容");
        }
        title = data.title;
        opf_file = data.opf;
        cover_href = data.cover;
        centralDirOffset = data.cd_off;
        centralDirSize = data.cd_size;
        for (var ie : data.entries) {
            registerEntry(new CentralDirEntry(ie.n, ie.c, ie.u, ie.o, ie.m, 0));
        }
        contents.addAll(data.spine);
        if (data.types != null) {
            resource_type.putAll(data.types);
        }
        if (data.toc != null) {
            for (var ti : data.toc) {
                toc.add(new TocEntry(ti.t, ti.p));
            }
        }
        buildSortedOffsets();
    }

    // 优先用 SQLite 里的索引 0 往返完成初始化; 没有(或损坏)则网络解析并落库
    public static LazyEpub open(String namespace, String epub_uri, ResourceInterface client, Database.DatabaseHelper db) throws Exception {
        var json = db.get_epub_index(namespace, epub_uri);
        if (json != null) {
            try {
                return new LazyEpub(epub_uri, client, json);
            } catch (Exception e) {
                db.delete_epub_index(namespace, epub_uri);
            }
        }
        var book = new LazyEpub(epub_uri, client);
        try {
            db.put_epub_index(namespace, epub_uri, book.index_json());
        } catch (Exception ignore) {
        }
        return book;
    }

    // ---- 索引持久化: 中央目录 + opf 解析结果, 路径做主键, 不做内容失效 (epub 默认不改) ----
    // v2: 新增目录(toc); 旧索引在 open 时判版本不符自动删除重建
    private static final int INDEX_VERSION = 2;

    static class IndexEntry {
        String n; // fileName
        long o;   // localHeaderOffset
        long c;   // compressedSize
        long u;   // uncompressedSize
        int m;    // compressionMethod
    }

    static class TocItem {
        String t; // title
        int p;    // spine 页码
    }

    static class IndexData {
        int v;
        String opf;
        String cover;
        String title;
        Integer cd_off;
        Integer cd_size;
        List<String> spine;
        List<TocItem> toc;
        HashMap<String, String> types;
        List<IndexEntry> entries;
    }

    public String index_json() {
        var data = new IndexData();
        data.v = INDEX_VERSION;
        data.opf = opf_file;
        data.cover = cover_href;
        data.title = title;
        data.cd_off = centralDirOffset;
        data.cd_size = centralDirSize;
        data.spine = contents;
        data.toc = new ArrayList<>();
        for (var e : toc) {
            var ti = new TocItem();
            ti.t = e.title;
            ti.p = e.page;
            data.toc.add(ti);
        }
        data.types = new HashMap<>(resource_type);
        // zip_dir 里有子路径别名指向同一条目, 按 fileName 去重后只存原始条目
        var byName = new HashMap<String, CentralDirEntry>();
        for (var e : zip_dir.values()) {
            byName.put(e.fileName, e);
        }
        data.entries = new ArrayList<>();
        for (var e : byName.values()) {
            var ie = new IndexEntry();
            ie.n = e.fileName;
            ie.o = e.localHeaderOffset;
            ie.c = e.compressedSize;
            ie.u = e.uncompressedSize;
            ie.m = e.compressionMethod;
            data.entries.add(ie);
        }
        return new Gson().toJson(data);
    }

    private void init_epub_dir() throws Exception {
        // 一次读取较大的文件尾部, 通常同时包含 EOCD 和整个中央目录 (也覆盖 ZIP 注释), 省掉一轮往返
        var slice = new Slice();
        slice.offset = -TAIL_SIZE;
        byte[] tail = file.open(uri, slice);
        if (!initCentralDirLocate(tail)) {
            throw new IllegalArgumentException("EOCD signature not found");
        }
        // 中央目录紧邻 EOCD 之前, 若已落在 tail 内(校验签名)则直接解析, 否则再单独请求
        int cdStart = eocdIdxInTail - centralDirSize;
        boolean cdInTail = cdStart >= 0
                && ByteBuffer.wrap(tail, cdStart, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == CENTRAL_DIR_SIGNATURE;
        if (cdInTail) {
            initCentralDirectory(Arrays.copyOfRange(tail, cdStart, eocdIdxInTail));
        } else {
            var s = new Slice();
            s.offset = centralDirOffset;
            s.size = centralDirSize;
            initCentralDirectory(file.open(uri, s));
        }
        buildSortedOffsets();
        // 不再预取所有条目的本地头(initCompressedOffset), 改为按需在 load 时惰性获取
        initContent();
    }

    private void buildSortedOffsets() {
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
        // 一趟选出所有引用了资源的元素, 按元素自带的属性取引用; LinkedHashSet 去重, 避免 Range 里出现重复区间
        var refs = new LinkedHashSet<String>();
        for (var page_num = from; page_num < to; ++page_num) {
            try {
                var filename = contents.get(page_num);
                var html = new String(load_file(filename), StandardCharsets.UTF_8);
                var doc = Jsoup.parse(html);
                for (var el : doc.select("script[src], img[src], link[href], image[xlink:href]")) {
                    var ref = el.hasAttr("src") ? el.attr("src")
                            : el.hasAttr("href") ? el.attr("href")
                            : el.attr("xlink:href");
                    ref = innerRef(ref);
                    if (ref != null) {
                        refs.add(ref);
                    }
                }
            } catch (Exception ignore) {
            }
        }
        try {
            load_file_to_cache(new ArrayList<>(refs));
        } catch (Exception ignore) {
        }
    }

    // 归一化页面里的资源引用: 外链/data URI 不在包内返回 null, 并去掉 #锚点 与 ?查询
    private static String innerRef(String ref) {
        if (ref == null || ref.contains("://") || ref.startsWith("data:")) {
            return null;
        }
        int i = ref.indexOf('#');
        if (i >= 0) {
            ref = ref.substring(0, i);
        }
        i = ref.indexOf('?');
        if (i >= 0) {
            ref = ref.substring(0, i);
        }
        return ref.isEmpty() ? null : ref;
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
        // 缓存未命中时回源加载: CSS 内部 url() 引用的字体/图片等不会被 prepare 的 Jsoup 扫描到,
        // 而 shouldInterceptRequest 本身运行在 WebView 后台线程, 允许网络 IO
        return load_file(filename);
    }

    @Override
    public String GetMediaType(String filename) {
        filename = cut(filename);
        var type = resource_type.get(filename);
        if (type != null) {
            return type;
        }
        // manifest 没覆盖到(或 href 编码/路径不一致)时按扩展名兜底, CSS/字体对 MIME 敏感
        var lower = filename.toLowerCase();
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "text/javascript";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".xhtml") || lower.endsWith(".html")) return "application/xhtml+xml";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".otf")) return "font/otf";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return null;
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
                eocdIdxInTail = i;
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
            registerEntry(entry);
            if (fileName.endsWith(".opf")) {
                // 正确做法应该是去META-INF/container.xml里找, 这样做应该也行
                opf_file = fileName;
            }
            // 计算下一个条目的起始位置
            position += 46 + fileNameLength + extraFieldLength + fileCommentLength;
        }
    }

    // 以完整文件名及其每级子路径为 key 注册条目 (页面里的相对引用常用子路径)
    private void registerEntry(CentralDirEntry entry) {
        Path path = Paths.get(entry.fileName);
        for (int i = 0; i < path.getNameCount(); i++) {
            var subPath = path.subpath(i, path.getNameCount()).toString();
            if (!zip_dir.containsKey(subPath)) {
                zip_dir.put(subPath, entry);
            }
        }
        zip_dir.put(entry.fileName, entry);
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
        String ncxHref = null;      // EPUB2 目录: toc.ncx
        String navHref = null;      // EPUB3 目录: properties="nav" 的导航文档
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
            if (props != null && props.contains("nav")) {
                navHref = href;
            }
            if ("application/x-dtbncx+xml".equals(mediaType)) {
                ncxHref = href;
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
        // EPUB2 规范: <spine toc="ncx的id">, 优先于按 media-type 找到的
        NodeList spines = doc.getElementsByTagName("spine");
        if (spines.getLength() > 0) {
            var tocId = ((Element) spines.item(0)).getAttribute("toc");
            var p = id_to_path.get(tocId);
            if (p != null) {
                ncxHref = p;
            }
        }
        try {
            initToc(ncxHref, navHref);
        } catch (Exception ignore) {
            // 目录解析失败不影响开书, 菜单里显示"本书没有目录"
        }
    }

    @Override
    public List<TocEntry> toc() {
        return toc;
    }

    // 解析目录文件 (优先 EPUB2 的 ncx, 其次 EPUB3 的 nav 文档), 把每个条目映射到 spine 页码
    private void initToc(String ncxHref, String navHref) throws Exception {
        // spine href -> 页码 的查找表; 与 registerEntry 同思路把每级子路径也注册进去,
        // 目录文件与 opf 的相对路径基准可能不同, 用后缀匹配兜底
        var lookup = new HashMap<String, Integer>();
        for (int i = 0; i < contents.size(); i++) {
            var full = cut(contents.get(i));
            var parts = full.split("/");
            for (int j = 0; j < parts.length; j++) {
                var sub = String.join("/", Arrays.copyOfRange(parts, j, parts.length));
                lookup.putIfAbsent(sub, i);
            }
        }
        if (ncxHref != null) {
            parseNcx(ncxHref, lookup);
        }
        if (toc.isEmpty() && navHref != null) {
            parseNav(navHref, lookup);
        }
    }

    private void parseNcx(String ncxHref, HashMap<String, Integer> lookup) throws Exception {
        var bytes = load_file(ncxHref);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        doc.getDocumentElement().normalize();
        // getElementsByTagName 按文档顺序返回所有 navPoint (嵌套的子章节自然摊平)
        NodeList points = doc.getElementsByTagName("navPoint");
        for (int i = 0; i < points.getLength(); i++) {
            Element np = (Element) points.item(i);
            var texts = np.getElementsByTagName("text");
            var srcs = np.getElementsByTagName("content");
            if (texts.getLength() == 0 || srcs.getLength() == 0) {
                continue;
            }
            // 自己的 navLabel 在子 navPoint 之前, item(0) 即本级标题
            var label = texts.item(0).getTextContent();
            var src = ((Element) srcs.item(0)).getAttribute("src");
            addTocEntry(label, src, lookup);
        }
    }

    private void parseNav(String navHref, HashMap<String, Integer> lookup) throws Exception {
        var html = new String(load_file(navHref), StandardCharsets.UTF_8);
        var doc = Jsoup.parse(html);
        org.jsoup.nodes.Element tocNav = null;
        for (var nav : doc.select("nav")) {
            if ("toc".equals(nav.attr("epub:type"))) {
                tocNav = nav;
                break;
            }
        }
        if (tocNav == null) {
            tocNav = doc.selectFirst("nav");
        }
        if (tocNav == null) {
            return;
        }
        for (var a : tocNav.select("a[href]")) {
            addTocEntry(a.text(), a.attr("href"), lookup);
        }
    }

    private void addTocEntry(String label, String src, HashMap<String, Integer> lookup) {
        if (label == null || src == null) {
            return;
        }
        label = label.trim();
        var page = resolveSpine(lookup, src);
        if (!label.isEmpty() && page != null) {
            toc.add(new TocEntry(label, page));
        }
    }

    // 目录条目的 href -> spine 页码: 去锚点/查询串后按后缀逐级匹配; 原样查不到再试 URL 解码后的
    private Integer resolveSpine(HashMap<String, Integer> lookup, String src) {
        int i = src.indexOf('#');
        if (i >= 0) {
            src = src.substring(0, i);
        }
        i = src.indexOf('?');
        if (i >= 0) {
            src = src.substring(0, i);
        }
        if (src.isEmpty()) {
            return null;
        }
        var page = matchSuffix(lookup, cut(src));
        if (page == null) {
            try {
                page = matchSuffix(lookup, cut(java.net.URLDecoder.decode(src, "UTF-8")));
            } catch (Exception ignore) {
            }
        }
        return page;
    }

    private static Integer matchSuffix(HashMap<String, Integer> lookup, String src) {
        while (!src.isEmpty()) {
            var idx = lookup.get(src);
            if (idx != null) {
                return idx;
            }
            int slash = src.indexOf('/');
            if (slash < 0) {
                return null;
            }
            src = src.substring(slash + 1);
        }
        return null;
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
        // 单文件路径保留"找不到就报错"的语义 (批量预取里则是跳过)
        if (!zip_dir.containsKey(filename)) {
            throw new IllegalArgumentException("no such file: " + filename);
        }
        var tmp = new ArrayList<String>();
        tmp.add(filename);
        load_file_to_cache(tmp);
        return cacheGet(filename);
    }

    private void load_file_to_cache(List<String> filenames) throws Exception {
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
            throw new IllegalArgumentException("无法解压epub: " + entry.compressionMethod);
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
