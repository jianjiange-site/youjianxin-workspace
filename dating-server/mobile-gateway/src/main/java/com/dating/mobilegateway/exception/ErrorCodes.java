package com.dating.mobilegateway.exception;

// Cross-cutting Result code 段 (与 user-service 对齐):
//   200 ok
//   401 未登录 / 403 封禁·权限 / 429 限流 / 500 系统 / 400 入参非法 / 10001+ 业务
public final class ErrorCodes {

    private ErrorCodes() {}

    public static final int OK = 200;

    public static final int UNAUTHENTICATED = 401;
    public static final int FORBIDDEN = 403;
    public static final int TOO_MANY_REQUESTS = 429;
    public static final int SYSTEM_ERROR = 500;
    public static final int INVALID_ARGUMENT = 400;
    public static final int NOT_FOUND = 404;

    // 鉴权域 10500+(预留与 user-service 10001~10499 区分)
    public static final int TOKEN_INVALID = 10501;
    public static final int TOKEN_EXPIRED = 10502;
    public static final int TOKEN_REVOKED = 10503;
    public static final int REFRESH_TOKEN_REUSED = 10504;
    public static final int REFRESH_TOKEN_DEVICE_MISMATCH = 10505;

    public static final int SMS_CODE_INVALID = 10601;
    public static final int SMS_CODE_EXPIRED = 10602;
    public static final int THIRD_PARTY_TOKEN_INVALID = 10603;

    public static final int UPSTREAM_UNAVAILABLE = 10901;
}
