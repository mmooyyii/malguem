package com.github.mmooyyii.malguem;

import com.google.gson.Gson;

import org.jsoup.Jsoup;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.DocumentBuilderFactory;

// epub: 在 LazyZip 的流式随机读之上, 按 opf 解释包内容 (spine 顺序 / 资源清单 / 目录 / 封面)
public class LazyEpub extends LazyZip implements Book {

    String title;
    List<String> contents; // page -> html
    List<TocEntry> toc = new ArrayList<>(); // 目录 (标题 -> spine 页码), 没有目录时为空

    ConcurrentHashMap<String, String> resource_type; // name -> media_type name

    String opf_file;
    String cover_href; // 封面图片在 epub 内的路径, 没有则为 null

    public LazyEpub(String epub_uri, ResourceInterface client) throws Exception {
        super(epub_uri, client);
        contents = new ArrayList<>();
        resource_type = new ConcurrentHashMap<>();
        readCentralDirectory();
        initContent();
    }

    // 从持久化索引恢复, 不发任何网络请求; 索引格式不兼容时抛异常, 由调用方删除索引后走网络重建
    public LazyEpub(String epub_uri, ResourceInterface client, String index_json) {
        super(epub_uri, client);
        contents = new ArrayList<>();
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

    @Override
    protected void onCentralDirEntry(CentralDirEntry entry) {
        if (entry.fileName.endsWith(".opf")) {
            // 正确做法应该是去META-INF/container.xml里找, 这样做应该也行
            opf_file = entry.fileName;
        }
    }

    // ---- 索引持久化: 中央目录 + opf 解析结果, 路径做主键, 不做内容失效 (epub 默认不改) ----
    // v2: 新增目录(toc); 旧索引在 open 时判版本不符自动删除重建
    private static final int INDEX_VERSION = 2;

    // 索引是否已是当前版本 (IndexCrawler 用它决定要不要批量重建)
    static boolean index_up_to_date(String json) {
        return json != null && json.contains("\"v\":" + INDEX_VERSION);
    }

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

    public String page(int page_num) {
        var filename = contents.get(page_num);
        filename = cut(filename);
        var html = cacheGet(filename);
        if (html != null) {
            return new String(html, StandardCharsets.UTF_8);
        }
        // 章节内容缺失时的兜底页; 这里拿不到 Context, 直接双语
        return "Failed to load this page / 本页加载失败";
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

    private long[] weights;    // 各 spine 项 html 的未压缩字节数, 懒算一次
    private long totalWeight;

    // 全书进度万分比, 按各章长度加权: 章长差几十倍很常见, 按章号均分的话读长章时进度几乎不动,
    // 读短章又一下跳很多. 权重取 zip 中央目录里的未压缩大小 —— 那是解析时就有的数据,
    // 不必读正文 (精确字数要解全文, 与按需加载的设计冲突; 百分比只需要相对比例, 字节数够用).
    // html 标签占比各书不同, 但同一本书里各章的标签密度接近, 不影响章与章的相对比例
    @Override
    public int progress(int page, int inner) {
        ensureWeights();
        if (totalWeight <= 0) {
            return Book.super.progress(page, inner); // 拿不到大小 (索引老/条目缺失) 时退回按章均分
        }
        long before = 0;
        for (int i = 0; i < page && i < weights.length; i++) {
            before += weights[i];
        }
        long cur = page >= 0 && page < weights.length ? weights[page] : 0;
        long pos = before + cur * Math.max(0, Math.min(10000, inner)) / 10000;
        return (int) Math.max(0, Math.min(10000, 10000L * pos / totalWeight));
    }

    private void ensureWeights() {
        if (weights != null) {
            return;
        }
        var w = new long[contents.size()];
        long sum = 0;
        for (int i = 0; i < contents.size(); i++) {
            var e = zip_dir.get(contents.get(i));
            w[i] = e == null ? 0 : e.uncompressedSize;
            sum += w[i];
        }
        totalWeight = sum;
        weights = w; // 最后赋值: 别让并发读者看到还没填完的数组
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
        return mediaTypeByExt(filename);
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
}
