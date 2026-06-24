package com.dating.post.exception;

import com.dating.post.constant.ErrorCode;
import lombok.Getter;

/**
 * 业务异常。
 * <p>
 * 在 service / manager 层抛出,由 {@link com.dating.post.grpc.GrpcExceptionInterceptor}
 * 兜底转 {@code BaseResponse(code != 0)},gRPC 状态码统一 OK(design §8.1)。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    // ---- 常用快捷构造 ----

    public static BizException postNotFound(long postId) {
        return new BizException(ErrorCode.POST_NOT_FOUND, "帖子不存在: " + postId);
    }

    public static BizException commentNotFound(long commentId) {
        return new BizException(ErrorCode.COMMENT_NOT_FOUND, "评论不存在: " + commentId);
    }

    public static BizException forbidden(String detail) {
        return new BizException(ErrorCode.FORBIDDEN, "权限不足: " + detail);
    }
}
