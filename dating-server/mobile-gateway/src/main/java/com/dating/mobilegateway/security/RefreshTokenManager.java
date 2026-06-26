package com.dating.mobilegateway.security;

import com.dating.mobilegateway.entity.AuthRefreshToken;
import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.manager.AuthRefreshTokenManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

// G3 security 层对 G2 AuthRefreshTokenManager 的薄包装 ——
//   - save:接收 JwtIssuer 出的 IssuedRefreshToken(已 hash),落库
//   - validate:对明文 refresh token 做 hash 反查 + 校验 revoked / expired / used (token reuse 检测)
//   - markUsedAndRotate / revokeByUser / revokeByUserDevice:语义透传
//
// 设计要点:
//   - 明文不入库,仅短暂在 RAM 中存在
//   - used_at != null 视为被重放(token reuse) —— 直接拒并撤销该用户该设备所有 refresh
//     (业界标准 rotation reuse detection)
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenManager {

    private final AuthRefreshTokenManager refreshTokenManager;

    public Long save(Long userId, String deviceId, IssuedRefreshToken issued) {
        if (issued == null) {
            throw new IllegalArgumentException("issued refresh token required");
        }
        return refreshTokenManager.insert(userId, deviceId, issued.tokenHash(), issued.expiresAt());
    }

    public AuthRefreshToken validate(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            throw new BizException(ErrorCodes.TOKEN_INVALID, "refresh token missing");
        }
        String hash = TokenHasher.sha256Hex(plainToken);
        AuthRefreshToken row = refreshTokenManager.findByHash(hash);
        if (row == null) {
            throw new BizException(ErrorCodes.TOKEN_INVALID, "refresh token unknown");
        }
        if (Boolean.TRUE.equals(row.getRevoked())) {
            throw new BizException(ErrorCodes.TOKEN_REVOKED, "refresh token revoked");
        }
        if (row.getExpiredAt() != null && row.getExpiredAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new BizException(ErrorCodes.TOKEN_EXPIRED, "refresh token expired");
        }
        if (row.getUsedAt() != null) {
            // Token reuse: 已轮换过的 refresh 被再次提交 —— 视作泄露,撤销该用户该设备所有 token。
            log.warn("refresh token reuse detected, userId={} deviceId={} oldId={}",
                    row.getUserId(), row.getDeviceId(), row.getId());
            refreshTokenManager.revokeByUserDevice(row.getUserId(), row.getDeviceId());
            throw new BizException(ErrorCodes.REFRESH_TOKEN_REUSED, "refresh token reused");
        }
        return row;
    }

    public void markUsedAndRotate(Long oldId, Long newId) {
        refreshTokenManager.markUsedAndRotate(oldId, newId);
    }

    public int revokeByUser(Long userId) {
        return refreshTokenManager.revokeByUser(userId);
    }

    public int revokeByUserDevice(Long userId, String deviceId) {
        return refreshTokenManager.revokeByUserDevice(userId, deviceId);
    }
}
