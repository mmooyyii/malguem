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
        Request request = new Request.Builder().url(make_dir_url(path))
                .method("PROPFIND", null).addHeader("Depth", "1")
                .addHeader("Authorization", Credentials.basic(username, password))
                .build();
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
                if (slices.size() == 1) {
                    var output = new HashMap<Slice, byte[]>();
                    output.put(slices.get(0), bytes);
                    return output;
                }
                return SplitMultipleRanges(bytes);
            }
        }
        throw new IOException("http 请求失败, 打不开" + url);
    }

    public static byte[] convertArrayListToByteArray(ArrayList<Byte> byteList) {
        byte[] byteArray = new byte[byteList.size()];
        for (int i = 0; i < byteList.size(); i++) {
            byteArray[i] = byteList.get(i);
        }
        return byteArray;
    }

    private HashMap<Slice, byte[]> SplitMultipleRanges(byte[] bytes) {
        var output = new HashMap<Slice, byte[]>();
        var buffer = new ArrayList<Byte>();
        Slice slice = null;
        var idx = 0;
        while (idx < bytes.length) {
            buffer.add(bytes[idx]);
            ++idx;
            if (EndsWithRN(buffer)) {
                if (StartsWith(buffer, "Content-Range:")) {
                    slice = extractRange(new String(convertArrayListToByteArray(buffer), StandardCharsets.UTF_8));
                } else if (StartsWith(buffer, "\r\n") && slice != null) {
                    output.put(slice, Arrays.copyOfRange(bytes, idx, idx + slice.size));
                    idx += slice.size;
                    slice = null;
                }
                buffer.clear();
            }
        }
        return output;
    }

    private static boolean StartsWith(ArrayList<Byte> buffer, String start) {
        byte[] startBytes = start.getBytes();
        int bufferSize = buffer.size();
        int startSize = startBytes.length;

        // 如果 buffer 的长度小于 start 字符串的字节数组长度，肯定不以 start 开头
        if (bufferSize < startSize) {
            return false;
        }

        // 从 buffer 的开头开始比较
        for (int i = 0; i < startSize; i++) {
            if (!buffer.get(i).equals(startBytes[i])) {
                return false;
            }
        }

        return true;
    }

    private Boolean EndsWithRN(ArrayList<Byte> buffer) {
        byte[] endBytes = "\r\n".getBytes();
        int bufferSize = buffer.size();
        int endSize = endBytes.length;

        // 如果 buffer 的长度小于 end 字符串的字节数组长度，肯定不以 end 结尾
        if (bufferSize < endSize) {
            return false;
        }
        // 从 buffer 的末尾开始比较
        for (int i = 0; i < endSize; i++) {
            if (buffer.get(bufferSize - endSize + i) != endBytes[i]) {
                return false;
            }
        }
        return true;
    }


    public static Slice extractRange(String contentRange) {
        // 定义正则表达式模式
        Pattern pattern = Pattern.compile("bytes (\\d+)-(\\d+)/");
        Matcher matcher = pattern.matcher(contentRange);
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





