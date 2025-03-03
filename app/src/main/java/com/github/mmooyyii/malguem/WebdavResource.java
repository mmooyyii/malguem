package com.github.mmooyyii.malguem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
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
    public List<ListItem> ls(int resource_id, List<String> path) throws Exception {
        var dirs = new ArrayList<ListItem>();
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

    public byte[] open(String uri, final DownloadListener listener) {
        var url = _url + uri;
        Request request = new Request.
                Builder().
                url(url).
                addHeader("Authorization", Credentials.basic(_username, _password)).
                build();

        final CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                latch.countDown();
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    long totalLength = response.body().contentLength();
                    InputStream inputStream = response.body().byteStream();

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long totalBytesRead = 0;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        totalBytesRead += bytesRead;
                        bos.write(buffer, 0, bytesRead);
                        // 计算下载进度
                        // 回调到主线程更新UI
                        listener.onProgress(totalBytesRead, totalLength);
                    }
                    inputStream.close();
                }
                latch.countDown();
            }
        });
        try {
            // 等待异步请求完成
            latch.await();
        } catch (InterruptedException e) {
            return null;
        }
        if (bos.size() == 0) {
            return null;
        }
        return bos.toByteArray();
    }
}





