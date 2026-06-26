package com.dating.mobilegateway.interceptor;

import com.dating.mobilegateway.constant.JwtClaims;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// 出站 gRPC 客户端拦截器:把 gateway 已认证的 userId / deviceId / traceId 透传到下游服务的 metadata。
//   - x-user-id: 来自 JwtAuthFilter 注入的请求属性(登录前 anonymous 请求没有,不透传)
//   - x-device-id: 同上;login-device / login-phone 在 service 层会手动设上下文,这里都用 request attr
//   - x-trace-id: 优先 request attr,缺失回退 MDC
//
// @GrpcGlobalClientInterceptor 让 net.devh 自动注册到所有 @GrpcClient 的 stub。
@Slf4j
@GrpcGlobalClientInterceptor
public class GrpcClientMetadataInterceptor implements ClientInterceptor {

    static final Metadata.Key<String> X_USER_ID =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> X_DEVICE_ID =
            Metadata.Key.of("x-device-id", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> X_TRACE_ID =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                applyFromRequestAttributes(headers);
                applyTraceIdFallback(headers);
                super.start(responseListener, headers);
            }
        };
    }

    private static void applyFromRequestAttributes(Metadata headers) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) {
            return;
        }
        Object userId = sra.getAttribute(JwtClaims.REQUEST_ATTR_USER_ID, RequestAttributes.SCOPE_REQUEST);
        if (userId != null) {
            headers.put(X_USER_ID, String.valueOf(userId));
        }
        Object deviceId = sra.getAttribute(JwtClaims.REQUEST_ATTR_DEVICE_ID, RequestAttributes.SCOPE_REQUEST);
        if (deviceId instanceof String s && !s.isBlank()) {
            headers.put(X_DEVICE_ID, s);
        }
        Object traceId = sra.getAttribute(JwtClaims.REQUEST_ATTR_TRACE_ID, RequestAttributes.SCOPE_REQUEST);
        if (traceId instanceof String s && !s.isBlank()) {
            headers.put(X_TRACE_ID, s);
        }
    }

    private static void applyTraceIdFallback(Metadata headers) {
        if (headers.containsKey(X_TRACE_ID)) {
            return;
        }
        String mdcTrace = MDC.get(JwtClaims.MDC_TRACE_ID);
        if (mdcTrace != null && !mdcTrace.isBlank()) {
            headers.put(X_TRACE_ID, mdcTrace);
        }
    }
}
