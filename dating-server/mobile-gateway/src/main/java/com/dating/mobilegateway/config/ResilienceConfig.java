package com.dating.mobilegateway.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

// /auth/* 限流注册:
//   - per-IP 限流,Resilience4j RateLimiterRegistry 内含 segment 上限,但 IP 维度长尾会导致内存膨胀;
//     生产建议放 Redis 限流。这里 G5 阶段只做单实例 in-memory,gateway 走单实例时够用。
//   - 默认 10 req / 1s,timeout 0 —— 不阻塞,直接判 429
@Slf4j
@Configuration
public class ResilienceConfig {

    @Bean
    public RateLimiterRegistry authRateLimiterRegistry(
            @Value("${gateway.rate-limit.auth.limit-per-second:10}") int limitPerSec,
            @Value("${gateway.rate-limit.auth.refresh-period-ms:1000}") long refreshPeriodMs) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(limitPerSec)
                .limitRefreshPeriod(Duration.ofMillis(refreshPeriodMs))
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiterRegistry.of(config);
    }

    // 全局默认实例 —— 给非 IP 维度的兜底限流 (目前未用,留扩展)
    @Bean
    public RateLimiter authGlobalRateLimiter(RateLimiterRegistry authRateLimiterRegistry) {
        return authRateLimiterRegistry.rateLimiter("auth-global");
    }
}
