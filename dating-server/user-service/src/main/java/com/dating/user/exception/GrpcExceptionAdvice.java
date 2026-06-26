package com.dating.user.exception;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.advice.GrpcAdvice;
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler;

// BizException / 系统异常 → gRPC StatusRuntimeException;
// 业务 code 通过 trailing metadata "x-biz-code" 透传给 mobile-gateway,gateway 再映射回 Result.code。
@Slf4j
@GrpcAdvice
public class GrpcExceptionAdvice {

    public static final Metadata.Key<String> BIZ_CODE =
            Metadata.Key.of("x-biz-code", Metadata.ASCII_STRING_MARSHALLER);

    @GrpcExceptionHandler(BizException.class)
    public StatusRuntimeException handleBiz(BizException ex) {
        log.warn("user-service biz error code={} msg={}", ex.getCode(), ex.getMessage());
        Metadata md = new Metadata();
        md.put(BIZ_CODE, String.valueOf(ex.getCode()));
        Status status = switch (ex.getCode()) {
            case ErrorCodes.UNAUTHENTICATED -> Status.UNAUTHENTICATED;
            case ErrorCodes.FORBIDDEN -> Status.PERMISSION_DENIED;
            case ErrorCodes.TOO_MANY_REQUESTS -> Status.RESOURCE_EXHAUSTED;
            case ErrorCodes.INVALID_ARGUMENT -> Status.INVALID_ARGUMENT;
            case ErrorCodes.SYSTEM_ERROR -> Status.INTERNAL;
            default -> Status.FAILED_PRECONDITION;
        };
        return status.withDescription(ex.getMessage()).asRuntimeException(md);
    }

    @GrpcExceptionHandler(StatusRuntimeException.class)
    public StatusRuntimeException passThrough(StatusRuntimeException ex) {
        return ex;
    }

    @GrpcExceptionHandler(IllegalArgumentException.class)
    public StatusRuntimeException handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("user-service illegal argument: {}", ex.getMessage());
        return Status.INVALID_ARGUMENT
                .withDescription(ex.getMessage() == null ? "invalid argument" : ex.getMessage())
                .asRuntimeException();
    }

    @GrpcExceptionHandler(Throwable.class)
    public StatusRuntimeException handleUnexpected(Throwable ex) {
        log.error("user-service unexpected error", ex);
        return Status.INTERNAL.withDescription("system error").asRuntimeException();
    }
}
