package com.github.mmooyyii.malguem;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WebdavResource implements ResourceInterface {
    String url;
    String username;
    String password;

    private final OkHttpClient client = new OkHttpClient();

    // 高延迟链路(如 Alist 代理网盘)上单连接吞吐有限, 大批量 range 拆成多条连接并行拉
    private static final int MAX_PARALLEL = 4;
    private static final long BYTES_PER_CONN = 512 * 1024; // 每多开一条连接所需的最小数据量
    private static final ExecutorService parallel_pool = Executors.newFixedThreadPool(MAX_PARALLEL);
    // 观测到服务器不支持 Range(返回 200 全量)后不再拆分, 避免并行请求各自拉全文件
    private volatile boolean range_unsupported = false;

    private final Pattern pattern_ranges = Pattern.compile("bytes (\\d+)-(\\d+)/");

    private static final byte[] ContentRanges = "Content-Range:".getBytes();


    WebdavResource(String url, String username, String passwd) {
        this.url = url;
        this.username = username;
        this.password = passwd;
    }

    public static String url_decode(String encoded) throws UnsupportedEncodingException {
        // 先将 %20 替换为一个临时占位符，这里用一个特殊字符，如 \u0000
        String temp = encoded.replace("+", "fuck java can't url decode correctly");
        // 使用标准的 URLDecoder 进行解码
        String decoded = URLDecoder.decode(temp, "UTF-8");
        // 再将占位符替换回空格
        return decoded.replace("fuck java can't url decode correctly", "+");
    }

    private String make_dir_url(List<String> path) {
        StringBuilder cur = new StringBuilder(url);
        for (var p : path) {
            cur.append("/").append(p);
        }
        return Objects.requireNonNull(HttpUrl.parse(cur.toString())).toString();
    }

    @Override
    public List<ListItem> ls(int resource_id, List<String> path) throws Exception {
        var dirs = new ArrayList<ListItem>();
        Request request = new Request.Builder().url(make_dir_url(path)).method("PROPFIND", null).addHeader("Depth", "1").addHeader("Authorization", Credentials.basic(username, password)).build();
        // 进行解码操作
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new HttpStatusException(response.code(), "ls " + make_dir_url(path));
            }
            if (response.code() == 207) {
                assert response.body() != null;
                String responseBody = response.body().string();
                String regex = "<D:href>(.*?)</D:href>";
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(responseBody);
                // 草, 为什么xml解不出来? 先用regex将就一下了.
                var ignore = matcher.find(); // 第一个是当前目录去掉它, 虽然我不知道这是不是ub
                while (matcher.find()) {
                    String content = matcher.group(1);
                    assert content != null;
                    content = url_decode(content);
                    var paths = content.split("/");
                    var lower = content.toLowerCase();
                    if (content.endsWith("/")) {
                        dirs.add(new ListItem(resource_id, paths[paths.length - 1], ListItem.FileType.Dir));
                    } else if (lower.endsWith(".epub") || lower.endsWith(".pdf")) {
                        dirs.add(new ListItem(resource_id, paths[paths.length - 1], ListItem.FileType.Epub));
                    }
                }
                return dirs;
            }
        }
        throw new IOException("http 请求失败, 打开目录" + make_dir_url(path));
    }

    @Override
    public String to_json() {
        var map = new HashMap<String, String>();
        map.put("type", "webdav");
        map.put("url", url);
        map.put("username", username);
        map.put("passwd", password);
        Gson gson = new Gson();
        return gson.toJson(map);
    }

    public byte[] open(String uri, Slice slice) throws Exception {
        var slices = new ArrayList<Slice>();
        slices.add(slice);
        return open(uri, slices).get(slice);
    }

    // 文件总大小: 用 Range 0-0 从 Content-Range 的分母拿 (dav 服务器对 HEAD 支持参差, 这个更稳)
    @Override
    public long size(String uri) throws Exception {
        var request = new Request.Builder().url(url + uri)
                .addHeader("Authorization", Credentials.basic(username, password))
                .addHeader("Range", "bytes=0-0")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 206) {
                var cr = response.header("Content-Range"); // 形如 bytes 0-0/12345
                if (cr != null) {
                    int i = cr.lastIndexOf('/');
                    if (i >= 0 && i + 1 < cr.length() && cr.charAt(i + 1) != '*') {
                        return Long.parseLong(cr.substring(i + 1).trim());
                    }
                }
            }
            if (response.code() == 200 && response.body() != null) {
                // 服务器不认 Range: 头里的 Content-Length 就是总大小, body 不消费直接关连接
                var len = response.body().contentLength();
                return len > 0 ? len : -1;
            }
        }
        return -1;
    }

    public HashMap<Slice, byte[]> open(String uri, List<Slice> slices) throws Exception {
        var groups = splitForParallel(slices);
        if (groups.size() <= 1) {
            return openOnce(uri, slices);
        }
        var futures = new ArrayList<Future<HashMap<Slice, byte[]>>>();
        for (var g : groups) {
            futures.add(parallel_pool.submit(() -> openOnce(uri, g)));
        }
        var output = new HashMap<Slice, byte[]>();
        Exception failed = null;
        for (var f : futures) {
            try {
                output.putAll(f.get());
            } catch (ExecutionException e) {
                failed = e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
            }
        }
        if (failed != null) {
            throw failed;
        }
        return output;
    }

    // 把多个 slice 按总字节量切成最多 MAX_PARALLEL 组; 按 offset 排序后连续切分, 相邻区间落在同组便于服务器合并
    private List<List<Slice>> splitForParallel(List<Slice> slices) {
        var output = new ArrayList<List<Slice>>();
        if (range_unsupported || slices.size() < 2) {
            output.add(slices);
            return output;
        }
        long total = 0;
        for (var s : slices) {
            if (s.offset == null || s.offset < 0 || s.size == null) {
                // 有后缀/开区间 range 时无法估算大小, 不拆分
                output.add(slices);
                return output;
            }
            total += s.size;
        }
        int groups = (int) Math.min(MAX_PARALLEL, total / BYTES_PER_CONN + 1);
        if (groups <= 1) {
            output.add(slices);
            return output;
        }
        var sorted = new ArrayList<>(slices);
        sorted.sort(Comparator.comparingInt(a -> a.offset));
        long target = (total + groups - 1) / groups;
        var cur = new ArrayList<Slice>();
        long acc = 0;
        for (var s : sorted) {
            cur.add(s);
            acc += s.size;
            if (acc >= target && output.size() < groups - 1) {
                output.add(cur);
                cur = new ArrayList<>();
                acc = 0;
            }
        }
        if (!cur.isEmpty()) {
            output.add(cur);
        }
        return output;
    }

    private HashMap<Slice, byte[]> openOnce(String uri, List<Slice> slices) throws Exception {
        var builder = new Request.Builder().url(url + uri).addHeader("Authorization", Credentials.basic(username, password));
        var sj = new StringJoiner(",");
        for (var slice : slices) {
            sj.add(slice.to_string());
        }
        builder.addHeader("Range", "bytes=" + sj);
        var request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new HttpStatusException(response.code(), "open " + url + uri);
            }
            {
                assert response.body() != null;
                var bytes = response.body().bytes();
                var contentType = response.header("Content-Type");
                if (contentType != null && contentType.toLowerCase().startsWith("multipart/byteranges")) {
                    return SplitMultipleRanges(bytes, slices);
                }
                if (response.code() == 200) {
                    // 服务器忽略了 Range 头, 返回了整个文件, 在本地按请求切片
                    return sliceLocally(bytes, slices);
                }
                // 单段 206 响应 (无论请求了几段)
                var output = new HashMap<Slice, byte[]>();
                if (slices.size() == 1) {
                    output.put(slices.get(0), bytes);
                    return output;
                }
                var contentRange = response.header("Content-Range");
                var returned = contentRange == null ? null : extractRange(contentRange);
                if (returned != null) {
                    // 服务器把多个 range 合并成一个更大的单段时, 按覆盖关系切回各请求区间
                    var parts = new ArrayList<Map.Entry<Slice, byte[]>>();
                    parts.add(new AbstractMap.SimpleEntry<>(returned, bytes));
                    return assignParts(parts, slices);
                }
                return output;
            }
        }
    }

    // 服务器不支持 Range 而返回整个文件时, 在本地按请求的 offset/size 切片
    private HashMap<Slice, byte[]> sliceLocally(byte[] bytes, List<Slice> slices) {
        range_unsupported = true; // 之后不再做并行拆分, 避免多条连接各自拉全量
        var output = new HashMap<Slice, byte[]>();
        for (var slice : slices) {
            int off = slice.offset;
            if (off < 0) {
                off = bytes.length + off; // 后缀 range, 如 -22
            }
            int size = slice.size == null ? bytes.length - off : slice.size;
            int from = Math.max(0, off);
            int to = Math.min(bytes.length, from + size);
            if (from <= to) {
                output.put(slice, Arrays.copyOfRange(bytes, from, to));
            }
        }
        return output;
    }

    static class Buffer {
        int buffer_index = 0;
        byte[] buffer = new byte[100];

        public int size() {
            return buffer_index;
        }

        void clear() {
            buffer_index = 0;
        }

        boolean StartWithRN() {
            return size() >= 2 && buffer[0] == '\r' && buffer[1] == '\n';
        }

        boolean EndWithRN() {
            return size() >= 2 && buffer[buffer_index - 2] == '\r' && buffer[buffer_index - 1] == '\n';
        }

        void add(byte b) {
            if (buffer_index == buffer.length) {
                // 必须用 copyOf 保留已有内容, 否则扩容会丢弃之前累积的字节
                buffer = Arrays.copyOf(buffer, buffer.length * 3 / 2);
            }
            buffer[buffer_index++] = b;
        }

        private boolean isContentRanges() {
            if (size() < ContentRanges.length) {
                return false;
            }
            for (int i = 0; i < ContentRanges.length; i++) {
                // HTTP 头名大小写不敏感 (RFC 7230), 有的服务器写 content-range
                if (Character.toLowerCase((char) buffer[i]) != Character.toLowerCase((char) ContentRanges[i])) {
                    return false;
                }
            }
            return true;
        }

        String to_string() {
            return new String(buffer, 0, buffer_index, StandardCharsets.US_ASCII);
        }
    }

    private HashMap<Slice, byte[]> SplitMultipleRanges(byte[] bytes, List<Slice> requested) {
        // 先解析出每个分段的实际区间与数据. 注意分段与请求不一定一一对应:
        // RFC 7233 允许服务器把相邻/间距很小的 range 合并成一个更大的分段返回,
        // 而 zip 里相邻条目的数据区间只隔一个几十字节的本地文件头, 极易被合并,
        // 所以不能按 offset 精确配对, 要按覆盖关系分配再裁切
        var parts = new ArrayList<Map.Entry<Slice, byte[]>>();
        var buffer = new Buffer();
        Slice slice = null;
        var idx = 0;
        while (idx < bytes.length) {
            buffer.add(bytes[idx]);
            ++idx;
            if (buffer.EndWithRN()) {
                if (buffer.isContentRanges()) {
                    slice = extractRange(buffer.to_string());
                } else if (buffer.StartWithRN() && slice != null) {
                    // 响应被截断时按实际长度截取, 覆盖判断会筛掉不完整的分段, 避免缓存补零的坏数据
                    var end = Math.min(idx + slice.size, bytes.length);
                    parts.add(new AbstractMap.SimpleEntry<>(slice, Arrays.copyOfRange(bytes, idx, end)));
                    idx += slice.size;
                    slice = null;
                }
                buffer.clear();
            }
        }
        return assignParts(parts, requested);
    }

    // 把服务器返回的分段按覆盖关系分配给请求的 slice: 分段 [pStart, pStart+len) 覆盖请求 [rStart, rEnd) 时裁出精确区间
    private static HashMap<Slice, byte[]> assignParts(List<Map.Entry<Slice, byte[]>> parts, List<Slice> requested) {
        var output = new HashMap<Slice, byte[]>();
        for (var req : requested) {
            if (req.offset == null || req.offset < 0 || req.size == null) {
                continue; // 后缀 range 不会以 multipart 返回, 不在此处理
            }
            long rStart = req.offset;
            long rEnd = rStart + req.size;
            for (var part : parts) {
                var data = part.getValue();
                long pStart = part.getKey().offset;
                if (rStart >= pStart && rEnd <= pStart + data.length) {
                    output.put(req, Arrays.copyOfRange(data, (int) (rStart - pStart), (int) (rEnd - pStart)));
                    break;
                }
            }
        }
        return output;
    }

    private Slice extractRange(String contentRange) {
        // 定义正则表达式模式
        Matcher matcher = pattern_ranges.matcher(contentRange);
        if (matcher.find()) {
            int start = Integer.parseInt(Objects.requireNonNull(matcher.group(1)));
            int end = Integer.parseInt(Objects.requireNonNull(matcher.group(2)));
            var slice = new Slice();
            slice.offset = start;
            slice.size = end - start + 1;
            return slice;
        }
        return null;
    }
}





