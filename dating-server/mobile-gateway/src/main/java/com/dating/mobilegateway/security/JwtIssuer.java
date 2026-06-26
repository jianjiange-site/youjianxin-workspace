package com.dating.mobilegateway.security;

import com.dating.mobilegateway.config.JwtKeyConfig;
import com.dating.mobilegateway.constant.JwtClaims;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

// 签发 access (RS256 JWT) 与 refresh (opaque 256-bit base64url)。
//   - access 含 uid / did / typ=access / jti(UUID) / iss / iat / exp
//   - refresh 不是 JWT,是 32-byte random base64url 字符串,SHA-256 hash 后存 PG
//   - 私钥从 JwtKeyConfig 取(内存中,不传第三方/不打日志)
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtIssuer {

    private final JwtKeyConfig keyConfig;
    private final SecureRandom secureRandom = new SecureRandom();

    public IssuedAccessToken issueAccess(Long userId, String deviceId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId required for access token");
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId required for access token");
        }
        String jti = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime exp = now.plus(Duration.ofMinutes(keyConfig.getAccessTtlMinutes()));
        String token = Jwts.builder()
                .issuer(keyConfig.getIssuer())
                .id(jti)
                .issuedAt(Date.from(now.toInstant()))
                .expiration(Date.from(exp.toInstant()))
                .claim(JwtClaims.CLAIM_USER_ID, userId)
                .claim(JwtClaims.CLAIM_DEVICE_ID, deviceId)
                .claim(JwtClaims.CLAIM_TOKEN_TYPE, JwtClaims.TOKEN_TYPE_ACCESS)
                .signWith(keyConfig.getPrivateKey(), Jwts.SIG.RS256)
                .compact();
        return new IssuedAccessToken(token, jti, exp);
    }

    public IssuedRefreshToken issueRefresh() {
        byte[] buf = new byte[32];
        secureRandom.nextBytes(buf);
        String plain = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        String hash = TokenHasher.sha256Hex(plain);
        OffsetDateTime exp = OffsetDateTime.now(ZoneOffset.UTC)
                .plus(Duration.ofDays(keyConfig.getRefreshTtlDays()));
        return new IssuedRefreshToken(plain, hash, exp);
    }
}
