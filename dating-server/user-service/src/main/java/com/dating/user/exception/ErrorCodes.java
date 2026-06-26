package com.dating.user.exception;

// Cross-cutting Result code 段 (与 mobile-gateway 对齐):
//   401 未登录 / 403 封禁·权限 / 429 限流 / 500 系统 / 400 入参非法 / 10001+ 业务。
public final class ErrorCodes {

    private ErrorCodes() {}

    public static final int UNAUTHENTICATED = 401;
    public static final int FORBIDDEN = 403;
    public static final int TOO_MANY_REQUESTS = 429;
    public static final int SYSTEM_ERROR = 500;
    public static final int INVALID_ARGUMENT = 400;

    public static final int USER_NOT_FOUND = 10001;
    public static final int USER_BANNED = 10002;
    public static final int USER_SUSPENDED = 10003;
    public static final int OPERATIONAL_BANNED = 10004;

    public static final int AVATAR_EXT_NOT_ALLOWED = 10101;
    public static final int AVATAR_SIZE_EXCEEDED = 10102;
    public static final int AVATAR_OBJECT_NOT_FOUND = 10103;
    public static final int AVATAR_OBJECT_KEY_INVALID = 10104;

    public static final int INTEREST_PIC_LIMIT_EXCEEDED = 10201;
    public static final int INTEREST_TEXT_LIMIT_EXCEEDED = 10202;

    public static final int PHONE_INVALID = 10301;
    public static final int THIRD_PARTY_INVALID = 10302;
    public static final int DEVICE_ID_INVALID = 10303;

    public static final int BATCH_SIZE_EXCEEDED = 10401;
}
