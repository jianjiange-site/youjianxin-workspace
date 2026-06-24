package com.dating.post.constant;

/**
 * 业务错误码(post-service-design §8.2)。
 * <p>
 * 0 = OK,4xxx = 业务错误,5xxx = 系统错误。gRPC 状态码统一 OK,业务错误靠 code 区分。
 */
public final class ErrorCode {

    public static final int OK = 0;

    public static final int CONTENT_EMPTY        = 4001;
    public static final int CONTENT_TOO_LONG     = 4002;
    public static final int IMAGE_COUNT_EXCEEDED = 4003;
    public static final int IMAGE_KEY_EMPTY      = 4004;
    public static final int POST_NOT_FOUND       = 4005;
    public static final int COMMENT_NOT_FOUND    = 4006;
    public static final int COMMENT_EMPTY        = 4007;
    public static final int COMMENT_TOO_LONG     = 4008;

    public static final int FORBIDDEN = 4030;

    public static final int INTERNAL_ERROR = 5000;

    private ErrorCode() {
    }
}
