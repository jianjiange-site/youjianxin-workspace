package com.dating.mobilegateway.filter;

import com.dating.mobilegateway.constant.JwtClaims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

// 优先级最高的过滤器:从请求头取 / 生成 traceId,放进 MDC 与请求属性,响应也带回去,
// finally 清 MDC。后续过滤器 / controller / service 拿到 traceId 用于排障。
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String traceId = req.getHeader(JwtClaims.HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(JwtClaims.MDC_TRACE_ID, traceId);
        req.setAttribute(JwtClaims.REQUEST_ATTR_TRACE_ID, traceId);
        resp.setHeader(JwtClaims.HEADER_TRACE_ID, traceId);
        try {
            chain.doFilter(req, resp);
        } finally {
            MDC.remove(JwtClaims.MDC_TRACE_ID);
        }
    }
}
