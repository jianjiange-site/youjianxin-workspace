package com.dating.mobilegateway.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dating.mobilegateway.entity.AuthRefreshToken;
import com.dating.mobilegateway.mapper.AuthRefreshTokenMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

// auth_refresh_token CRUD:
//   insert(hash, ttl)        — 落 hash + 过期时间,明文绝不入库 (hash 由 G3 security 算)
//   findByHash(hash)         — 唯一索引查 token (refresh 时校验)
//   markUsedRotate(old, new) — 旧 token used_at + rotated_to_id ← new.id (轮换链)
//   revokeByUser(userId)     — 用户主动登出 / 改密 / 强制登出 → 撤销该用户所有未过期 token
//   revokeByDevice(userId, deviceId)
//                            — 单设备登出
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRefreshTokenManager {

    private final AuthRefreshTokenMapper authRefreshTokenMapper;

    public Long insert(Long userId, String deviceId, String tokenHash, OffsetDateTime expiredAt) {
        if (userId == null || deviceId == null || deviceId.isBlank()
                || tokenHash == null || tokenHash.isBlank() || expiredAt == null) {
            throw new IllegalArgumentException("refresh token insert requires userId / deviceId / hash / expiredAt");
        }
        AuthRefreshToken t = new AuthRefreshToken();
        t.setUserId(userId);
        t.setDeviceId(deviceId);
        t.setTokenHash(tokenHash);
        t.setIssuedAt(OffsetDateTime.now());
        t.setExpiredAt(expiredAt);
        t.setRevoked(false);
        authRefreshTokenMapper.insert(t);
        return t.getId();
    }

    public AuthRefreshToken findByHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return null;
        }
        LambdaQueryWrapper<AuthRefreshToken> qw = new LambdaQueryWrapper<>();
        qw.eq(AuthRefreshToken::getTokenHash, tokenHash).last("limit 1");
        return authRefreshTokenMapper.selectOne(qw);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markUsedAndRotate(Long oldId, Long newId) {
        if (oldId == null || newId == null) {
            throw new IllegalArgumentException("oldId / newId required for rotate");
        }
        LambdaUpdateWrapper<AuthRefreshToken> uw = new LambdaUpdateWrapper<>();
        uw.eq(AuthRefreshToken::getId, oldId)
                .set(AuthRefreshToken::getUsedAt, OffsetDateTime.now())
                .set(AuthRefreshToken::getRotatedToId, newId);
        authRefreshTokenMapper.update(null, uw);
    }

    public int revokeByUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        LambdaUpdateWrapper<AuthRefreshToken> uw = new LambdaUpdateWrapper<>();
        uw.eq(AuthRefreshToken::getUserId, userId)
                .eq(AuthRefreshToken::getRevoked, false)
                .set(AuthRefreshToken::getRevoked, true)
                .set(AuthRefreshToken::getRevokedAt, OffsetDateTime.now());
        return authRefreshTokenMapper.update(null, uw);
    }

    public int revokeByUserDevice(Long userId, String deviceId) {
        if (userId == null || deviceId == null || deviceId.isBlank()) {
            return 0;
        }
        LambdaUpdateWrapper<AuthRefreshToken> uw = new LambdaUpdateWrapper<>();
        uw.eq(AuthRefreshToken::getUserId, userId)
                .eq(AuthRefreshToken::getDeviceId, deviceId)
                .eq(AuthRefreshToken::getRevoked, false)
                .set(AuthRefreshToken::getRevoked, true)
                .set(AuthRefreshToken::getRevokedAt, OffsetDateTime.now());
        return authRefreshTokenMapper.update(null, uw);
    }
}
