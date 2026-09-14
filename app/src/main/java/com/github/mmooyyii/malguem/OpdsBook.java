package com.github.mmooyyii.malguem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// OPDS 漫画走服务端页流 (OPDS-PSE): Komga/Kavita 这类书库会在 feed 里给一个带 {pageNumber}
// 占位的链接和总页数, 按页号取单张图就行, 不用把整本 zip 拖下来随机读.
//
// 这是 OPDS 漫画唯一能快起来的路子 —— Komga 的下载端点根本不认 Range: 实测带 Range 的请求
// 照样回 200 + 完整文件 (单段和 multi-range 都试过). LazyZip 的按需读碰上它会退化成
// "每读一小段拉一次整本": 一本 62MB 的全彩漫画, 读中央目录一次、翻每页各一次, 全是 62MB.
// 同一本书走 PSE, 单页 39KB.
//
// 只对有 PSE 链接的书用, 而服务端只对图片型漫画给这个链接, 所以不会撞上小说:
// 文字 epub 既没有这个 link, 硬打它的 pages 端点也是 500. 见 Books.open 的分派
public class OpdsBook implements Book {

    // 页面图片在 WebView 里的引用前缀, 同 LazyCbz 的思路: 用页码做 src, 避开文件名编码问题
    private static final String PAGE_PREFIX = "pse-page/";
    private static final int CACHE_PAGES = 8; // 双栏 + 预读用得到的页数, 再多没意义

    // feed 里一个书条目的页流信息
    static class Stream {
        String href;      // 带 {pageNumber} 占位的页面地址
        int count;        // pse:count, 总页数
        String thumbHref; // 缩略图, 当封面用 (几 KB, 比开整本快几个数量级)
    }

    private final Stream stream;
    private final OpdsResource source;
    // prepare() 预取的页面; WebView 回头 GetResource 时直接命中, 不再跑一趟网络.
    // prepare 在后台线程、GetResource 在 WebView 线程, 要同步
    private final Map<Integer, byte[]> cache = Collections.synchronizedMap(
            new LinkedHashMap<Integer, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
                    return size() > CACHE_PAGES;
                }
            });

    OpdsBook(Stream stream, OpdsResource source) {
        this.stream = stream;
        this.source = source;
    }

    // 一页一张图; 外层 ComicActivity 会往 head 里注入背景/适配样式
    @Override
    public String page(int page_num) {
        if (page_num < 0 || page_num >= stream.count) {
            return "";
        }
        return "<html><head><meta charset=\"utf-8\"/></head>"
                + "<body style=\"margin:0\"><img src=\"" + PAGE_PREFIX + page_num + ".jpg\"/></body></html>";
    }

    @Override
    public void prepare(int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(to, stream.count); i++) {
            if (cache.containsKey(i)) {
                continue;
            }
            try {
                cache.put(i, fetchPage(i));
            } catch (Exception ignore) {
                // 预取失败不算错, GetResource 那边还会再试一次
            }
        }
    }

    @Override
    public int total_pages() {
        return stream.count;
    }

    @Override
    public byte[] GetResource(String filename) throws Exception {
        var index = pageIndexOf(filename);
        if (index < 0) {
            throw new IllegalArgumentException("不是页面请求: " + filename);
        }
        var hit = cache.get(index);
        if (hit != null) {
            return hit;
        }
        var bytes = fetchPage(index);
        cache.put(index, bytes);
        return bytes;
    }

    // 服务端统一转好的 jpeg (href 里带 convert=jpeg); 真拿回别的格式 WebView 也能按内容认出来
    @Override
    public String GetMediaType(String filename) {
        return "image/jpeg";
    }

    // 封面优先用 feed 里的缩略图: 几 KB 就够, 不必为了列表上一张小图去拉整页
    @Override
    public byte[] cover() throws Exception {
        if (stream.thumbHref != null && !stream.thumbHref.isEmpty()) {
            try {
                return source.fetch(stream.thumbHref);
            } catch (Exception ignore) {
                // 缩略图没了就退回第一页
            }
        }
        return stream.count > 0 ? fetchPage(0) : null;
    }

    // PSE 规范: 页号从 0 排到 N-1, 和内部页码正好对齐, 不用换算.
    // (别想当然改成 index+1: Komga 上首页会变成第二页, 翻到最后一页则直接 400 "Page number does not exist")
    private byte[] fetchPage(int index) throws Exception {
        var n = String.valueOf(index);
        // 占位符可能原样给, 也可能被 feed 转义过
        var url = stream.href.replace("{pageNumber}", n).replace("%7BpageNumber%7D", n);
        return source.fetch(url);
    }

    // "pse-page/3.jpg" -> 3; 不是页面请求返回 -1
    private static int pageIndexOf(String filename) {
        var name = filename;
        while (name.startsWith(".") || name.startsWith("/")) {
            name = name.substring(1);
        }
        if (!name.startsWith(PAGE_PREFIX)) {
            return -1;
        }
        var rest = name.substring(PAGE_PREFIX.length());
        int dot = rest.indexOf('.');
        if (dot >= 0) {
            rest = rest.substring(0, dot);
        }
        try {
            return Integer.parseInt(rest);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
