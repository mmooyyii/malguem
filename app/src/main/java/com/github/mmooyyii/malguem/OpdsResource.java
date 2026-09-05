package com.github.mmooyyii.malguem;

import com.google.gson.Gson;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;

// OPDS 目录数据源: Komga / Kavita / Calibre-Web / LANraragi 等自建书库的通用接口 (OPDS 1.2, Atom XML).
// 目录条目按标题映射进现有的 pwd(目录名列表) 模型, 每次 ls 从根 feed 逐级走到当前层;
// 书条目是 HTTP 下载链接, Range 读复用 WebdavResource 的 http 客户端 (multipart/206/200 兜底都是现成的)
public class OpdsResource implements ResourceInterface {

    private static final int MAX_FEED_PAGES = 50; // 单层 feed 跟随 rel=next 分页的上限, Komga 默认每页几十条

    final String url; // 根 catalog 地址, 如 http://host:25600/opds/v1.2/catalog
    final String username;
    final String password;

    private transient OkHttpClient client;
    private transient WebdavResource http; // 只用它的 open(): base 传空串, 直接喂绝对下载链接
    private final transient HashMap<String, String> hrefByUri = new HashMap<>(); // "/系列/书.epub" -> 绝对下载链接

    OpdsResource(String url, String username, String password) {
        this.url = url == null ? "" : url;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    static OpdsResource fromMap(HashMap<String, String> m) {
        return new OpdsResource(m.get("url"), m.get("username"), m.get("password"));
    }

    @Override
    public String to_json() {
        var map = new HashMap<String, String>();
        map.put("type", "opds");
        map.put("url", url);
        map.put("username", username);
        map.put("password", password);
        return new Gson().toJson(map);
    }

    @Override
    public List<ListItem> ls(int resource_id, List<String> path) throws Exception {
        var feedUrl = url;
        for (var dirName : path) {
            String nextUrl = null;
            for (var e : fetchFeed(feedUrl)) {
                if (e.navHref != null && e.title.equals(dirName)) {
                    nextUrl = e.navHref;
                    break;
                }
            }
            if (nextUrl == null) {
                throw new IOException("OPDS 目录不存在: " + dirName);
            }
            feedUrl = nextUrl;
        }
        var prefix = new StringBuilder();
        for (var p : path) {
            prefix.append("/").append(p);
        }
        var out = new ArrayList<ListItem>();
        for (var e : fetchFeed(feedUrl)) {
            if (e.bookHref != null) {
                var name = e.title + e.ext;
                out.add(new ListItem(resource_id, name, ListItem.FileType.Epub));
                // 顺手缓存 uri -> 下载链接, open 时免得再走一遍目录
                hrefByUri.put(prefix + "/" + name, e.bookHref);
            } else if (e.navHref != null) {
                out.add(new ListItem(resource_id, e.title, ListItem.FileType.Dir));
            }
        }
        return out;
    }

    @Override
    public byte[] open(String uri, Slice slice) throws Exception {
        var slices = new ArrayList<Slice>();
        slices.add(slice);
        return open(uri, slices).get(slice);
    }

    @Override
    public HashMap<Slice, byte[]> open(String uri, List<Slice> slices) throws Exception {
        return http().open(resolveHref(uri), slices);
    }

    private WebdavResource http() {
        if (http == null) {
            http = new WebdavResource("", username, password);
        }
        return http;
    }

    // 书的 uri -> 绝对下载链接; 直开场景(最近阅读/封面加载)实例是新建的, 缓存没命中就按目录名从根走一遍
    private String resolveHref(String uri) throws Exception {
        var cached = hrefByUri.get(uri);
        if (cached != null) {
            return cached;
        }
        var parts = uri.split("/");
        var path = new ArrayList<String>();
        for (int i = 1; i < parts.length - 1; i++) {
            path.add(parts[i]);
        }
        ls(0, path); // 会把该层所有书塞进 hrefByUri
        var href = hrefByUri.get(uri);
        if (href == null) {
            throw new IOException("OPDS 里找不到: " + uri);
        }
        return href;
    }

    private static class Entry {
        String title;
        String navHref;  // 子目录 feed
        String bookHref; // epub/pdf 下载链接
        String ext;      // ".epub" / ".pdf"
    }

    // 拉一层 feed, 跟随 rel=next 分页; 标题内的 "/" 会破坏 pwd 模型, 换成 "∕"; 同层重名加序号去重
    private List<Entry> fetchFeed(String feedUrl) throws Exception {
        var out = new ArrayList<Entry>();
        var seen = new HashSet<String>();
        var next = feedUrl;
        for (int page = 0; next != null && page < MAX_FEED_PAGES; page++) {
            var b = new Request.Builder().url(next);
            if (!username.isEmpty() || !password.isEmpty()) {
                b.addHeader("Authorization", Credentials.basic(username, password));
            }
            try (var response = httpClient().newCall(b.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new HttpStatusException(response.code(), "OPDS " + next);
                }
                next = parseFeed(response.body().bytes(), next, out, seen);
            }
        }
        return out;
    }

    private OkHttpClient httpClient() {
        if (client == null) {
            client = new OkHttpClient();
        }
        return client;
    }

    // 解析一页 feed, 条目追加进 out; 返回下一页地址(没有则 null)
    private String parseFeed(byte[] xml, String baseUrl, List<Entry> out, HashSet<String> seen) throws Exception {
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        doc.getDocumentElement().normalize();
        String nextUrl = null;
        var feedChildren = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < feedChildren.getLength(); i++) {
            var node = feedChildren.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            var el = (Element) node;
            var tag = localName(el);
            if ("link".equals(tag)) {
                if ("next".equals(el.getAttribute("rel"))) {
                    nextUrl = resolve(baseUrl, el.getAttribute("href"));
                }
                continue;
            }
            if (!"entry".equals(tag)) {
                continue;
            }
            var entry = parseEntry(el, baseUrl);
            if (entry == null) {
                continue;
            }
            // 同层重名去重, 否则 pwd/uri 按名字寻址会互相遮蔽
            var base = entry.title;
            for (int n = 2; !seen.add(entry.title + (entry.ext == null ? "" : entry.ext)); n++) {
                entry.title = base + " (" + n + ")";
            }
            out.add(entry);
        }
        return nextUrl;
    }

    private Entry parseEntry(Element el, String baseUrl) {
        var entry = new Entry();
        var children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            var node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            var child = (Element) node;
            var tag = localName(child);
            if ("title".equals(tag) && entry.title == null) {
                var t = child.getTextContent();
                entry.title = t == null ? "" : t.trim().replace('/', '∕');
            } else if ("link".equals(tag)) {
                var rel = child.getAttribute("rel");
                var type = child.getAttribute("type");
                var href = child.getAttribute("href");
                if (href.isEmpty()) {
                    continue;
                }
                if (rel.startsWith("http://opds-spec.org/acquisition")) {
                    // 只认 epub 与 pdf, 其余格式(cbz/mobi 等)跳过
                    if (type.contains("epub")) {
                        entry.bookHref = resolve(baseUrl, href);
                        entry.ext = ".epub";
                    } else if (entry.bookHref == null && type.contains("pdf")) {
                        entry.bookHref = resolve(baseUrl, href);
                        entry.ext = ".pdf";
                    }
                } else if (entry.navHref == null
                        && (type.contains("profile=opds-catalog") || "subsection".equals(rel))) {
                    entry.navHref = resolve(baseUrl, href);
                }
            }
        }
        if (entry.title == null || entry.title.isEmpty()) {
            return null;
        }
        // 既有下载链接就当书, 否则有子目录链接才算目录
        if (entry.bookHref == null && entry.navHref == null) {
            return null;
        }
        if (entry.bookHref != null) {
            entry.navHref = null;
        }
        return entry;
    }

    // Atom 常见无前缀, 个别 feed 会带 (如 atom:entry), 统一取冒号后的本名
    private static String localName(Element el) {
        var name = el.getNodeName();
        int i = name.indexOf(':');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    private static String resolve(String baseUrl, String href) {
        try {
            return URI.create(baseUrl).resolve(href).toString();
        } catch (Exception e) {
            return href;
        }
    }
}
