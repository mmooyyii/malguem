package com.github.mmooyyii.malguem;

import java.io.IOException;

// 带 HTTP 状态码的 IO 异常, 供 Errors 分类出 认证失败/404 等可行动的提示
public class HttpStatusException extends IOException {
    public final int code;

    public HttpStatusException(int code, String detail) {
        super("http " + code + " " + detail);
        this.code = code;
    }
}
