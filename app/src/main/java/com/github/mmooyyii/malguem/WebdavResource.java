package com.github.mmooyyii.malguem;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
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
            if (response.isSuccessful() && response.code() == 207) {
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
                    if (content.endsWith("/")) {
                        dirs.add(new ListItem(resource_id, paths[paths.length - 1], ListItem.FileType.Dir));
                    } else if (content.endsWith(".epub")) {
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

    public HashMap<Slice, byte[]> open(String uri, List<Slice> slices) throws Exception {
        var builder = new Request.Builder().url(url + uri).addHeader("Authorization", Credentials.basic(username, password));
        var sj = new StringJoiner(",");
        for (var slice : slices) {
            sj.add(slice.to_string());
        }
        builder.addHeader("Range", "bytes=" + sj);
        var request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
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
                    for (var slice : slices) {
                        if (slice.offset.equals(returned.offset)) {
                            output.put(slice, bytes);
                        }
                    }
                }
                return output;
            }
        }
        throw new IOException("http 请求失败, 打不开" + url);
    }

    // 服务器不支持 Range 而返回整个文件时, 在本地按请求的 offset/size 切片
    private HashMap<Slice, byte[]> sliceLocally(byte[] bytes, List<Slice> slices) {
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
                if (buffer[i] != ContentRanges[i]) {
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
        // 按 offset 建索引, 把解析出的分段映射回调用方持有的 Slice 实例,
        // 否则响应里 size=end-start+1 的新 Slice 与请求 Slice 的 equals/hashCode 不一致, 查不到结果
        var byOffset = new HashMap<Integer, Slice>();
        for (var s : requested) {
            byOffset.put(s.offset, s);
        }
        var output = new HashMap<Slice, byte[]>();
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
                    var data = Arrays.copyOfRange(bytes, idx, idx + slice.size);
                    var key = byOffset.get(slice.offset);
                    output.put(key != null ? key : slice, data);
                    idx += slice.size;
                    slice = null;
                }
                buffer.clear();
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





