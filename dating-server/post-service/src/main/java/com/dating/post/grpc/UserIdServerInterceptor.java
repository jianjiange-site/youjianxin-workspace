package com.dating.post.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.MDC;

/**
 * 从 gRPC Metadata 取 {@code x-user-id} 注入 Context + MDC。
 * <p>
 * 约定(post-service-design §8.1):
 * <ul>
 *   <li>mobile-gateway 解 JWT 后塞 Metadata,本服务信任 gateway 注入</li>
 *   <li>**禁止把 user_id 作为业务字段塞 proto request**(除非语义是「操作目标」如 ListUserPosts)</li>
 * </ul>
 */
@GrpcGlobalServerInterceptor
public class UserIdServerInterceptor implements ServerInterceptor {

    /** Metadata key:{@code x-user-id}。 */
    public static final Metadata.Key<String> USER_ID_KEY =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);

    /** Context key,业务侧通过 {@link #currentUserId()} 取。 */
    public static final Context.Key<Long> USER_ID_CONTEXT = Context.key("currentUserId");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT, RespT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        long userId = 0L;
        String raw = headers.get(USER_ID_KEY);
        if (raw != null && !raw.isBlank()) {
            try {
                userId = Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                // header 非法当未鉴权处理(userId=0)
            }
        }

        MDC.put("userId", String.valueOf(userId));
        try {
            Context ctx = Context.current().withValue(USER_ID_CONTEXT, userId);
            return Contexts.interceptCall(ctx, call, headers, next);
        } finally {
            MDC.remove("userId");
        }
    }

    /** 业务侧统一取当前调用方 user_id;未鉴权时返 0。 */
    public static long currentUserId() {
        Long v = USER_ID_CONTEXT.get();
        return v == null ? 0L : v;
    }
}
