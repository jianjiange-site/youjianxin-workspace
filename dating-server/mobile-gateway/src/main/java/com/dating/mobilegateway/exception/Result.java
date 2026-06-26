package com.dating.mobilegateway.exception;

import lombok.Getter;

// 统一响应包装:HTTP 状态码总是 200,业务用 code 区分。
@Getter
public class Result<T> {

    private final int code;
    private final String message;
    private final T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCodes.OK, "ok", data);
    }

    public static <T> Result<T> ok() {
        return new Result<>(ErrorCodes.OK, "ok", null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
