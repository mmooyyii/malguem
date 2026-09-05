package com.github.mmooyyii.malguem;

import android.content.Context;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

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
            // LazyEpub/PdfRenderer 的解析失败: zip 结构/opf/pdf 打不开
            if (t instanceof IllegalArgumentException || t instanceof SecurityException) {
                return ctx.getString(R.string.err_bad_file);
            }
        }
        var msg = e.getMessage();
        return msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg;
    }
}
