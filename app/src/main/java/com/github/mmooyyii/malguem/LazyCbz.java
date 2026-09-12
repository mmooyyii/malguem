package com.github.mmooyyii.malguem;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

// cbz: zip 里一堆图片, 一张图就是一页. 和 epub 共用 LazyZip 的流式随机读,
// 开书只拿中央目录, 翻到哪页才拉哪张图的字节区间.
// 包里没有 opf 那样的顺序清单, 阅读顺序就按文件名自然序 (page2 排在 page10 前面)
public class LazyCbz extends LazyZip implements Book {

    // 页面图片在 WebView 里的引用前缀: 用页码而不是真实文件名做 src,
    // 免去文件名里的空格/#/中文在 url 里编解码不一致的麻烦 (扩展名保留, MIME 仍按它判)
    private static final String PAGE_PREFIX = "cbz-page/";

    private final List<String> pages = new ArrayList<>(); // 页码 -> 包内图片路径
    private final List<String> imageEntries = new ArrayList<>(); // 解析中央目录时收集, 排序后进 pages

    public LazyCbz(String cbz_uri, ResourceInterface client) throws Exception {
        super(cbz_uri, client);
        readCentralDirectory();
        imageEntries.sort(LazyCbz::naturalCompare);
        pages.addAll(imageEntries);
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("cbz 里没有图片");
        }
    }

    // 从持久化索引恢复, 不发任何网络请求; 格式不兼容时抛异常, 由调用方删除索引后走网络重建
    public LazyCbz(String cbz_uri, ResourceInterface client, String index_json) {
        super(cbz_uri, client);
        var data = new Gson().fromJson(index_json, CbzIndex.class);
        if (data == null || data.v != INDEX_VERSION || !"cbz".equals(data.kind)
                || data.entries == null || data.pages == null || data.pages.isEmpty()) {
            throw new IllegalArgumentException("索引格式不兼容");
        }
        centralDirOffset = data.cd_off;
        centralDirSize = data.cd_size;
        for (var ie : data.entries) {
            registerEntry(new CentralDirEntry(ie.n, ie.c, ie.u, ie.o, ie.m, 0));
        }
        pages.addAll(data.pages);
        buildSortedOffsets();
    }

    // 优先用 SQLite 里的索引 0 往返完成初始化; 没有(或损坏)则网络解析并落库
    public static LazyCbz open(String namespace, String cbz_uri, ResourceInterface client, Database.DatabaseHelper db) throws Exception {
        var json = db.get_epub_index(namespace, cbz_uri);
        if (json != null) {
            try {
                return new LazyCbz(cbz_uri, client, json);
            } catch (Exception e) {
                db.delete_epub_index(namespace, cbz_uri);
            }
        }
        var book = new LazyCbz(cbz_uri, client);
        try {
            db.put_epub_index(namespace, cbz_uri, book.index_json());
        } catch (Exception ignore) {
        }
        return book;
    }

    @Override
    protected void onCentralDirEntry(CentralDirEntry entry) {
        if (isPageImage(entry.fileName)) {
            imageEntries.add(entry.fileName);
        }
    }

    // 只收当页面用的图片: 跳过目录条目、macOS 打包垃圾 (__MACOSX/ 与 ._ 开头的 AppleDouble)、非图片
    private static boolean isPageImage(String name) {
        if (name.endsWith("/") || name.startsWith("__MACOSX/")) {
            return false;
        }
        var base = name.substring(name.lastIndexOf('/') + 1);
        if (base.isEmpty() || base.startsWith(".")) {
            return false;
        }
        var lower = base.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".avif");
    }

    // 自然序: 文件名里的数字段按数值比, 免得 10 排到 2 前面 (漫画里 page1/page10/page2 太常见).
    // 其余部分不分大小写按字典序
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                // 去掉前导零后先比位数再比字典序, 这样不用担心页码长到 long 装不下
                var na = stripLeadingZeros(a.substring(si, i));
                var nb = stripLeadingZeros(b.substring(sj, j));
                if (na.length() != nb.length()) {
                    return na.length() - nb.length();
                }
                int c = na.compareTo(nb);
                if (c != 0) {
                    return c;
                }
            } else {
                char la = Character.toLowerCase(ca), lb = Character.toLowerCase(cb);
                if (la != lb) {
                    return la - lb;
                }
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    // ---- 索引持久化: 中央目录 + 页面顺序 ----
    private static final int INDEX_VERSION = 1;

    static boolean index_up_to_date(String json) {
        return json != null && json.contains("\"kind\":\"cbz\"") && json.contains("\"v\":" + INDEX_VERSION);
    }

    static class CbzIndex {
        int v;
        String kind; // "cbz", 和 epub 索引共用一张表, 靠它区分
        Integer cd_off;
        Integer cd_size;
        List<String> pages;
        List<LazyEpub.IndexEntry> entries;
    }

    public String index_json() {
        var data = new CbzIndex();
        data.v = INDEX_VERSION;
        data.kind = "cbz";
        data.cd_off = centralDirOffset;
        data.cd_size = centralDirSize;
        data.pages = pages;
        // zip_dir 里有子路径别名指向同一条目, 按 fileName 去重后只存原始条目
        var byName = new java.util.HashMap<String, CentralDirEntry>();
        for (var e : zip_dir.values()) {
            byName.put(e.fileName, e);
        }
        data.entries = new ArrayList<>();
        for (var e : byName.values()) {
            var ie = new LazyEpub.IndexEntry();
            ie.n = e.fileName;
            ie.o = e.localHeaderOffset;
            ie.c = e.compressedSize;
            ie.u = e.uncompressedSize;
            ie.m = e.compressionMethod;
            data.entries.add(ie);
        }
        return new Gson().toJson(data);
    }

    // 一页就是一张图; 外层 ComicActivity 会往 head 里注入背景/适配样式
    @Override
    public String page(int page_num) {
        if (page_num < 0 || page_num >= pages.size()) {
            return "";
        }
        var name = pages.get(page_num);
        int dot = name.lastIndexOf('.');
        var ext = dot >= 0 ? name.substring(dot) : "";
        return "<html><head><meta charset=\"utf-8\"/></head>"
                + "<body style=\"margin:0\"><img src=\"" + PAGE_PREFIX + page_num + ext + "\"/></body></html>";
    }

    @Override
    public void prepare(int from, int to) {
        var files = new ArrayList<String>();
        for (int i = Math.max(0, from); i < Math.min(to, pages.size()); i++) {
            files.add(pages.get(i));
        }
        try {
            load_file_to_cache(files);
        } catch (Exception ignore) {
        }
    }

    @Override
    public int total_pages() {
        return pages.size();
    }

    @Override
    public byte[] GetResource(String filename) throws Exception {
        return load_file(resolve(filename));
    }

    @Override
    public String GetMediaType(String filename) {
        return mediaTypeByExt(resolve(filename));
    }

    // WebView 请求的路径 -> 包内真实路径: 页面图片走 "cbz-page/页码.ext", 其余原样当包内路径
    private String resolve(String filename) {
        var name = cut(filename);
        if (!name.startsWith(PAGE_PREFIX)) {
            return name;
        }
        var rest = name.substring(PAGE_PREFIX.length());
        int dot = rest.indexOf('.');
        if (dot >= 0) {
            rest = rest.substring(0, dot);
        }
        try {
            int idx = Integer.parseInt(rest);
            if (idx >= 0 && idx < pages.size()) {
                return pages.get(idx);
            }
        } catch (NumberFormatException ignore) {
        }
        return name;
    }

    // 封面就是第一页
    public byte[] cover() throws Exception {
        if (pages.isEmpty()) {
            return null;
        }
        return load_file(pages.get(0));
    }
}
