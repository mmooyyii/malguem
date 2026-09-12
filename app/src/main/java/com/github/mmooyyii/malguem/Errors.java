package com.github.mmooyyii.malguem;

import android.content.Context;

import com.google.gson.JsonParseException;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import jcifs.smb.SmbAuthException;

// 把底层异常翻译成用户能行动的提示: 网络不通 / 账号密码错 / 文件没了 / 文件坏了.
// 识别不了的保留原始信息, 方便远程排查
public class Errors {

    public static String describe(Context ctx, Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof SocketTimeoutException || t instanceof NoRouteToHostException) {
                return ctx.getString(R.string.err_network);
            }
            if (t instanceof SmbAuthException) {
                return ctx.getString(R.string.err_auth);
            }
            if (t instanceof HttpStatusException) {
                int code = ((HttpStatusException) t).code;
                if (code == 401 || code == 403) {
                    return ctx.getString(R.string.err_auth);
                }
                if (code == 404 || code == 410) {
                    return ctx.getString(R.string.err_not_found);
                }
                return ctx.getString(R.string.err_http, code);
            }
            if (t instanceof FileNotFoundException) {
                return ctx.getString(R.string.err_not_found);
            }
            // LazyEpub 的解析失败: zip 结构/opf 打不开
            if (t instanceof IllegalArgumentException || t instanceof SecurityException) {
                return ctx.getString(R.string.err_bad_file);
            }
        }
        var msg = e.getMessage();
        return msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    // 更新源排查专用: 比 describe 粒度细一档, 直接点名断在 DNS/TLS/连接/HTTP 哪一环.
    // describe 把这些统统归成"连不上服务器", 那对"为什么 7 个源全灭"毫无帮助 —
    // 全是 DNS 失败指向 DNS 被污染或没配, 全是 TLS 失败指向 SNI 阻断, 出现 HTTP 码则说明网络本身是通的.
    public static String diagnose(Context ctx, Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) {
                return ctx.getString(R.string.diag_dns);
            }
            // 子类在前: SSLHandshakeException extends SSLException
            if (t instanceof SSLHandshakeException) {
                return ctx.getString(R.string.diag_tls);
            }
            if (t instanceof SSLException) {
                return ctx.getString(R.string.diag_ssl);
            }
            if (t instanceof SocketTimeoutException) {
                return ctx.getString(R.string.diag_timeout);
            }
            if (t instanceof ConnectException) {
                return ctx.getString(R.string.diag_refused);
            }
            if (t instanceof NoRouteToHostException) {
                return ctx.getString(R.string.diag_unreachable);
            }
            if (t instanceof HttpStatusException) {
                return "HTTP " + ((HttpStatusException) t).code;
            }
            if (t instanceof JsonParseException) {
                return ctx.getString(R.string.diag_bad_json);
            }
        }
        // 认不出来的原样抛出类名+消息, 远程排查时这比"未知错误"有用得多
        var msg = e.getMessage();
        var name = e.getClass().getSimpleName();
        return msg == null || msg.isEmpty() ? name : name + ": " + msg;
    }
}
