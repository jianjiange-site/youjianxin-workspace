package com.dating.mobilegateway.filter;

import com.dating.mobilegateway.constant.JwtClaims;
import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.exception.Result;
import com.dating.mobilegateway.security.JwtVerifier;
import com.dating.mobilegateway.security.ParsedAccessToken;
import com.dating.mobilegateway.security.TokenBlacklistManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

// 验签 access JWT → 查黑名单 → 解出 userId/deviceId/jti 入 request attr + MDC。
// 白名单路径(登录 / 健康检查 / swagger / actuator)跳过验签。
// 验签失败 / 黑名单命中:直接写 JSON Result(HTTP 200,业务 code 401/10501),
// 因为过滤器层 RestControllerAdvice 接不到。
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    // logout 不在白名单 —— 需要 JWT 上下文识别 jti 拉黑。其余 auth 端点都是匿名入口。
    private static final List<String> WHITELIST = List.of(
            "/api/v1/auth/send-sms-code",
            "/api/v1/auth/login-phone",
            "/api/v1/auth/login-third-party",
            "/api/v1/auth/login-device",
            "/api/v1/auth/refresh",
            "/api/v1/health/**",
            "/callback/**",
            "/v3/api-docs/**",
            "/v3/api-docs",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/swagger-resources/**",
            "/webjars/**",
            "/actuator/**",
            "/favicon.ico"
    );

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final JwtVerifier jwtVerifier;
    private final TokenBlacklistManager blacklistManager;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        for (String p : WHITELIST) {
            if (MATCHER.match(p, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String auth = req.getHeader(JwtClaims.HEADER_AUTHORIZATION);
        if (auth == null || !auth.startsWith(JwtClaims.HEADER_BEARER_PREFIX)) {
            writeError(resp, ErrorCodes.UNAUTHENTICATED, "missing bearer token");
            return;
        }
        String token = auth.substring(JwtClaims.HEADER_BEARER_PREFIX.length()).trim();
        ParsedAccessToken parsed;
        try {
            parsed = jwtVerifier.parse(token);
        } catch (BizException ex) {
            writeError(resp, ex.getCode(), ex.getMessage());
            return;
        }
        if (blacklistManager.isBlacklisted(parsed.jti())) {
            writeError(resp, ErrorCodes.TOKEN_REVOKED, "token revoked");
            return;
        }
        req.setAttribute(JwtClaims.REQUEST_ATTR_USER_ID, parsed.userId());
        req.setAttribute(JwtClaims.REQUEST_ATTR_DEVICE_ID, parsed.deviceId());
        req.setAttribute(JwtClaims.REQUEST_ATTR_JTI, parsed.jti());
        req.setAttribute(JwtClaims.REQUEST_ATTR_ACCESS_EXP, parsed.expiresAt());
        MDC.put(JwtClaims.MDC_USER_ID, String.valueOf(parsed.userId()));
        MDC.put(JwtClaims.MDC_DEVICE_ID, parsed.deviceId());
        try {
            chain.doFilter(req, resp);
        } finally {
            MDC.remove(JwtClaims.MDC_USER_ID);
            MDC.remove(JwtClaims.MDC_DEVICE_ID);
        }
    }

    private void writeError(HttpServletResponse resp, int code, String message) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] body = objectMapper.writeValueAsBytes(Result.fail(code, message));
        resp.getOutputStream().write(body);
    }
}
