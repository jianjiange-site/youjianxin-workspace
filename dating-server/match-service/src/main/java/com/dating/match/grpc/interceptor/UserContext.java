package com.dating.match.grpc.interceptor;

import io.grpc.Context;
import io.grpc.Metadata;

// gRPC 入站上下文：
//   - x-user-id（写类 RPC 主体身份，如划卡 / 记录访问；读类 RPC 也用它做"当前用户"）
//   - x-device-id（链路透传）
//   - x-trace-id（链路 ID，缺省由拦截器生成 UUID）
// service 层通过 UserContext.callerUserId() 读取当前用户。
public final class UserContext {

    public static final Metadata.Key<String> X_USER_ID =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> X_DEVICE_ID =
            Metadata.Key.of("x-device-id", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> X_TRACE_ID =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);

    public static final Context.Key<Long> CALLER_USER_ID = Context.key("callerUserId");
    public static final Context.Key<String> DEVICE_ID = Context.key("deviceId");
    public static final Context.Key<String> TRACE_ID = Context.key("traceId");

    private UserContext() {}

    public static Long callerUserId() {
        return CALLER_USER_ID.get();
    }

    public static String deviceId() {
        return DEVICE_ID.get();
    }

    public static String traceId() {
        return TRACE_ID.get();
    }
}
