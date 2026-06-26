package com.dating.mobilegateway.constant;

// JWT claim key + 请求头 + MDC key + Redis 黑名单前缀 + 请求属性 key 收口。
//   - access token claims: uid (userId), did (deviceId), typ ("access"), jti, exp, iss, iat
//   - refresh token: 不是 JWT,而是 opaque random 256-bit base64url 字符串,SHA-256 hash 后落库
public final class JwtClaims {

    private JwtClaims() {}

    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_DEVICE_ID = "did";
    public static final String CLAIM_TOKEN_TYPE = "typ";

    public static final String TOKEN_TYPE_ACCESS = "access";

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_BEARER_PREFIX = "Bearer ";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_DEVICE_ID = "X-Device-Id";

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_DEVICE_ID = "deviceId";

    public static final String REQUEST_ATTR_USER_ID = "gateway.userId";
    public static final String REQUEST_ATTR_DEVICE_ID = "gateway.deviceId";
    public static final String REQUEST_ATTR_JTI = "gateway.jti";
    public static final String REQUEST_ATTR_ACCESS_EXP = "gateway.accessExp";
    public static final String REQUEST_ATTR_TRACE_ID = "gateway.traceId";

    public static final String REDIS_KEY_BLACKLIST_PREFIX = "gateway:auth:blacklist:";
}
