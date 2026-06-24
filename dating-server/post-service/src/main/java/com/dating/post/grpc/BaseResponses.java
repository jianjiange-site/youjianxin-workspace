package com.dating.post.grpc;

import com.dating.common.proto.BaseResponse;
import com.dating.post.constant.ErrorCode;
import com.dating.post.exception.BizException;

/**
 * BaseResponse 构造工具(post-service-design §8.1)。
 * <p>
 * gRPC 状态码统一 OK,业务错误靠 {@code BaseResponse.code} 区分。
 * RPC 实现里 try-catch BizException → {@link #fail(BizException)};未知异常 → {@link #internalError(Throwable)}。
 */
public final class BaseResponses {

    public static BaseResponse ok() {
        return BaseResponse.newBuilder().setCode(ErrorCode.OK).build();
    }

    public static BaseResponse fail(BizException e) {
        return BaseResponse.newBuilder()
                .setCode(e.getCode())
                .setMessage(e.getMessage() == null ? "" : e.getMessage())
                .build();
    }

    public static BaseResponse internalError(Throwable e) {
        return BaseResponse.newBuilder()
                .setCode(ErrorCode.INTERNAL_ERROR)
                .setMessage(e.getMessage() == null ? "internal error" : e.getMessage())
                .build();
    }

    private BaseResponses() {
    }
}
