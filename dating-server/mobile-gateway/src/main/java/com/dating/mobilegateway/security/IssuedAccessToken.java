package com.dating.mobilegateway.security;

import java.time.OffsetDateTime;

// JwtIssuer.issueAccess() 出参 —— access JWT 明文 + jti + 过期时间。
//   - token 明文返给前端
//   - jti 用于撤销(JwtAuthFilter 读出后查 TokenBlacklistManager)
//   - expiresAt 用于黑名单 TTL 计算
public record IssuedAccessToken(String token, String jti, OffsetDateTime expiresAt) {
}
