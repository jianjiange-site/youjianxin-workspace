package com.dating.mobilegateway.security;

import java.time.OffsetDateTime;

// JwtVerifier.parse() 出参 —— 验签 + claim 抽取后的结构化结果。
//   - userId / deviceId 注入到请求上下文给 controller / service 使用
//   - jti 用于查 TokenBlacklistManager
//   - expiresAt 仅作信息用,验签时已校验过期
public record ParsedAccessToken(Long userId, String deviceId, String jti, OffsetDateTime expiresAt) {
}
