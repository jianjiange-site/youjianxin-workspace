package com.dating.match.grpc.interceptor;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.MDC;

import java.util.UUID;

// 全局 ServerInterceptor：把 metadata（x-user-id / x-device-id / x-trace-id）写进 gRPC Context + MDC。
// MDC 在 interceptCall 内 set，在 ListenerWrapper.onComplete/onCancel 里 clear，
// 保证日志 traceId/userId 跨整个 RPC handler 生命周期可用。
@Slf4j
@GrpcGlobalServerInterceptor
public class UserContextServerInterceptor implements ServerInterceptor {

    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_USER_ID = "userId";

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String userIdStr = headers.get(UserContext.X_USER_ID);
        String deviceId = headers.get(UserContext.X_DEVICE_ID);
        String traceIdHeader = headers.get(UserContext.X_TRACE_ID);
        String traceId = (traceIdHeader == null || traceIdHeader.isBlank())
                ? UUID.randomUUID().toString()
                : traceIdHeader;

        Long userId = null;
        if (userIdStr != null && !userIdStr.isBlank()) {
            try {
                userId = Long.parseLong(userIdStr.trim());
            } catch (NumberFormatException nfe) {
                log.warn("x-user-id metadata not parsable as long: {}", userIdStr);
            }
        }

        Context ctx = Context.current()
                .withValue(UserContext.CALLER_USER_ID, userId)
                .withValue(UserContext.DEVICE_ID, deviceId)
                .withValue(UserContext.TRACE_ID, traceId);

        MDC.put(MDC_TRACE_ID, traceId);
        if (userId != null) {
            MDC.put(MDC_USER_ID, String.valueOf(userId));
        }

        ServerCall.Listener<ReqT> delegate;
        try {
            delegate = Contexts.interceptCall(ctx, call, headers, next);
        } catch (RuntimeException e) {
            clearMdc();
            throw e;
        }

        return new ForwardingListener<>(delegate);
    }

    private static void clearMdc() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_USER_ID);
    }

    // 包装 Listener，RPC handler 在异步线程上回调时由各回调方法重新清理 MDC。
    private static final class ForwardingListener<ReqT> extends ServerCall.Listener<ReqT> {
        private final ServerCall.Listener<ReqT> delegate;

        ForwardingListener(ServerCall.Listener<ReqT> delegate) {
            this.delegate = delegate;
        }

        @Override public void onMessage(ReqT message) { delegate.onMessage(message); }
        @Override public void onHalfClose() { delegate.onHalfClose(); }
        @Override public void onReady() { delegate.onReady(); }

        @Override
        public void onCancel() {
            try { delegate.onCancel(); } finally { clearMdc(); }
        }

        @Override
        public void onComplete() {
            try { delegate.onComplete(); } finally { clearMdc(); }
        }
    }
}
