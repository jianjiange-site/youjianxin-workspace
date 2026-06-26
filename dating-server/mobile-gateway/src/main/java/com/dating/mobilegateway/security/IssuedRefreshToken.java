package com.dating.mobilegateway.security;

import java.time.OffsetDateTime;

// JwtIssuer.issueRefresh() 出参 —— refresh token 明文 + SHA-256 hash + 过期时间。
//   - plainToken 返给前端(只此一次)
//   - tokenHash 落 auth_refresh_token.token_hash (明文绝不入库)
//   - expiresAt 落 auth_refresh_token.expired_at
public record IssuedRefreshToken(String plainToken, String tokenHash, OffsetDateTime expiresAt) {
}
