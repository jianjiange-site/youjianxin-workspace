package com.dating.mobilegateway.client;

import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

// 下游 gRPC StatusRuntimeException 到 gateway 业务异常的统一映射。
//   - UNAUTHENTICATED  → 401
//   - PERMISSION_DENIED → 403
//   - RESOURCE_EXHAUSTED → 429
//   - INVALID_ARGUMENT  → 400
//   - NOT_FOUND         → 404
//   - DEADLINE_EXCEEDED / UNAVAILABLE → 10901 UPSTREAM_UNAVAILABLE
//   - 其他              → 500
//
// 下游 GrpcExceptionAdvice 抛 BizException 时,trailers 不带 code,只能按 Status 大致还原;
// 真要透传业务 code,后续可让 user-service 把 BizException.code 塞 Status.description。
@Slf4j
public final class GrpcStatusMapper {

    private GrpcStatusMapper() {}

    public static BizException map(StatusRuntimeException sre) {
        Status status = sre.getStatus();
        Status.Code code = status.getCode();
        String desc = status.getDescription();
        String message = (desc == null || desc.isBlank()) ? code.name() : desc;
        log.warn("upstream grpc fail code={} desc={}", code, desc);
        return switch (code) {
            case UNAUTHENTICATED  -> new BizException(ErrorCodes.UNAUTHENTICATED, message);
            case PERMISSION_DENIED -> new BizException(ErrorCodes.FORBIDDEN, message);
            case RESOURCE_EXHAUSTED -> new BizException(ErrorCodes.TOO_MANY_REQUESTS, message);
            case INVALID_ARGUMENT -> new BizException(ErrorCodes.INVALID_ARGUMENT, message);
            case NOT_FOUND        -> new BizException(ErrorCodes.NOT_FOUND, message);
            case DEADLINE_EXCEEDED, UNAVAILABLE -> new BizException(ErrorCodes.UPSTREAM_UNAVAILABLE, "upstream unavailable");
            default                -> new BizException(ErrorCodes.SYSTEM_ERROR, "upstream error");
        };
    }
}
