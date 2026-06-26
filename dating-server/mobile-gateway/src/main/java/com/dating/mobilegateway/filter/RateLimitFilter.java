package com.dating.mobilegateway.filter;

import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.exception.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// /api/v1/auth/* 路径 per-IP 限流:
//   - 命中令牌(acquirePermission)→ 放行
//   - 失败 → 直接 429 Result(过滤器层 RestControllerAdvice 接不到)
//
// Order:在 TraceId(+10) / Cors(+20) 之后,JwtAuthFilter(+30) 之前 —— 限流要快速失败,
// 不消耗 JWT 验签算力。
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";

    private final RateLimiterRegistry authRateLimiterRegistry;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith(AUTH_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIp(req);
        // RateLimiterRegistry.rateLimiter(name) 复用同名实例,name 收口为 "auth:<ip>"
        RateLimiter limiter = authRateLimiterRegistry.rateLimiter("auth:" + ip);
        if (!limiter.acquirePermission()) {
            writeTooManyRequests(resp, ip);
            return;
        }
        chain.doFilter(req, resp);
    }

    private void writeTooManyRequests(HttpServletResponse resp, String ip) throws IOException {
        log.warn("rate limit hit ip={}", ip);
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] body = objectMapper.writeValueAsBytes(
                Result.fail(ErrorCodes.TOO_MANY_REQUESTS, "auth rate limit, retry later"));
        resp.getOutputStream().write(body);
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }
}
