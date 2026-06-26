package com.dating.mobilegateway.security;

import com.dating.mobilegateway.config.JwtKeyConfig;
import com.dating.mobilegateway.constant.JwtClaims;
import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

// 验签 access JWT 并抽出 claims。失败抛 BizException,由 JwtAuthFilter 转 401。
//   - 签名失败 / 结构损坏 → TOKEN_INVALID
//   - 过期 → TOKEN_EXPIRED
//   - typ 非 access → TOKEN_INVALID (避免 refresh 被当 access 使用)
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtVerifier {

    private final JwtKeyConfig keyConfig;

    public ParsedAccessToken parse(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCodes.TOKEN_INVALID, "token missing");
        }
        Claims claims;
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(keyConfig.getPublicKey())
                    .requireIssuer(keyConfig.getIssuer())
                    .build()
                    .parseSignedClaims(token);
            claims = jws.getPayload();
        } catch (ExpiredJwtException ex) {
            throw new BizException(ErrorCodes.TOKEN_EXPIRED, "token expired");
        } catch (JwtException | IllegalArgumentException ex) {
            // 签名 / 结构 / 不符 issuer 全部归类为 invalid,不向调用方泄露细节。
            log.debug("jwt verify failed: {}", ex.getMessage());
            throw new BizException(ErrorCodes.TOKEN_INVALID, "token invalid");
        }
        Object typ = claims.get(JwtClaims.CLAIM_TOKEN_TYPE);
        if (!JwtClaims.TOKEN_TYPE_ACCESS.equals(String.valueOf(typ))) {
            throw new BizException(ErrorCodes.TOKEN_INVALID, "token type mismatch");
        }
        Long userId = readLong(claims, JwtClaims.CLAIM_USER_ID);
        String deviceId = String.valueOf(claims.get(JwtClaims.CLAIM_DEVICE_ID));
        String jti = claims.getId();
        if (userId == null || userId <= 0 || deviceId == null || deviceId.isBlank() || jti == null || jti.isBlank()) {
            throw new BizException(ErrorCodes.TOKEN_INVALID, "token claims incomplete");
        }
        OffsetDateTime exp = claims.getExpiration() == null
                ? null
                : OffsetDateTime.ofInstant(claims.getExpiration().toInstant(), ZoneOffset.UTC);
        return new ParsedAccessToken(userId, deviceId, jti, exp);
    }

    private static Long readLong(Claims claims, String key) {
        Object v = claims.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
