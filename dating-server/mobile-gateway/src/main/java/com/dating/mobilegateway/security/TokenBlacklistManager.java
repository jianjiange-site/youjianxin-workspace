package com.dating.mobilegateway.security;

import com.dating.mobilegateway.constant.JwtClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

// Access JWT 黑名单 —— logout / 改密 / 强制下线时拉黑 jti,直到 JWT 自然过期。
// Key: gateway:auth:blacklist:<jti>  TTL = JWT 剩余有效期(秒,>= 1)
// 不存 value(只存在性判断);取出时 EXISTS = true 即视为已撤销。
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBlacklistManager {

    private final StringRedisTemplate redis;

    public void blacklist(String jti, OffsetDateTime accessExpiresAt) {
        if (jti == null || jti.isBlank() || accessExpiresAt == null) {
            return;
        }
        long ttlSec = Duration.between(Instant.now(), accessExpiresAt.toInstant()).getSeconds();
        if (ttlSec <= 0) {
            // 已过期就不必入黑名单 —— JwtVerifier 会先于黑名单判过期。
            return;
        }
        String key = JwtClaims.REDIS_KEY_BLACKLIST_PREFIX + jti;
        redis.opsForValue().set(key, "1", Duration.ofSeconds(ttlSec));
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        Boolean exists = redis.hasKey(JwtClaims.REDIS_KEY_BLACKLIST_PREFIX + jti);
        return Boolean.TRUE.equals(exists);
    }
}
