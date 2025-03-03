package com.github.mmooyyii.malguem;

import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

import java.io.IOException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import okhttp3.*;

import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WebdavResource implements ResourceInterface {
    String _url;
    String _username;
    String _password;

    private final OkHttpClient client = new OkHttpClient();

    WebdavResource(String url, String username, String passwd) {
        _url = url;
        _username = username;
        _password = passwd;
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
        StringBuilder cur = new StringBuilder(_url);
        for (var p : path) {
            cur.append("/").append(p);
        }
        return Objects.requireNonNull(HttpUrl.parse(cur.toString())).toString();
    }

    @Override
    public List<File> ls(int resource_id, List<String> path) throws Exception {
        var dirs = new ArrayList<File>();
        Request request = new Request.Builder().url(make_dir_url(path))
                .method("PROPFIND", null).addHeader("Depth", "1")
                .addHeader("Authorization", Credentials.basic(_username, _password))
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
                matcher.find(); // 第一个是当前目录去掉它, 虽然我不知道这是不是ub
                while (matcher.find()) {
                    String content = matcher.group(1);
                    content = url_decode(content);
                    var paths = content.split("/");
                    if (content.endsWith("/")) {
                        dirs.add(new File(resource_id, paths[paths.length - 1], File.FileType.Dir));
                    } else if (content.endsWith(".epub")) {
                        dirs.add(new File(resource_id, paths[paths.length - 1], File.FileType.Epub));
                    }
                }
                return dirs;
            }
        }
        throw new IOException("http 请求失败, 打开目录" + make_dir_url(path));
    }


    public byte[] open(String uri, Slice slice) throws Exception {
        long startTime = System.currentTimeMillis();
        var url = _url + uri;
        var builder = new Request.Builder().url(url).
                addHeader("Authorization", Credentials.basic(_username, _password));
        if (slice != null) {
            builder.addHeader("Range", "bytes=" + slice.to_string());
        }
        var request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                assert response.body() != null;
                var bytes = response.body().bytes();
                if (slice != null) {
                    Log.d("lazy_epub", (System.currentTimeMillis() - startTime) + "ms " + uri + "range: " + slice.to_string());
                }
                return bytes;
            }
        }
        throw new IOException("http 请求失败, 打不开" + url);
    }

    public byte[][] open(String uri, Slice[] slice) throws Exception {
        return null;
    }
}





