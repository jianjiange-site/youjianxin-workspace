package com.dating.mobilegateway.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dating.mobilegateway.entity.AuthDevice;
import com.dating.mobilegateway.mapper.AuthDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

// auth_device CRUD:
//   findByUserAndDevice  — 唯一索引 (user_id, device_id) WHERE NOT deleted
//   upsert               — 命中已有 → 增量改非空字段 + touchLastSeen;未命中 → insert
//   touchLastSeen        — 仅刷 last_seen_at,避免每次 refresh 重写一整行
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthDeviceManager {

    private final AuthDeviceMapper authDeviceMapper;

    public AuthDevice findByUserAndDevice(Long userId, String deviceId) {
        if (userId == null || deviceId == null || deviceId.isBlank()) {
            return null;
        }
        LambdaQueryWrapper<AuthDevice> qw = new LambdaQueryWrapper<>();
        qw.eq(AuthDevice::getUserId, userId)
                .eq(AuthDevice::getDeviceId, deviceId)
                .last("limit 1");
        return authDeviceMapper.selectOne(qw);
    }

    // 业务幂等 upsert:存在则增量改 + touchLastSeen;不存在则 insert。
    // 返回最终 AuthDevice (主键填回)。
    public AuthDevice upsert(AuthDevice fresh) {
        if (fresh == null || fresh.getUserId() == null
                || fresh.getDeviceId() == null || fresh.getDeviceId().isBlank()
                || fresh.getPlatform() == null) {
            throw new IllegalArgumentException("auth_device upsert requires userId / deviceId / platform");
        }
        AuthDevice existing = findByUserAndDevice(fresh.getUserId(), fresh.getDeviceId());
        OffsetDateTime now = OffsetDateTime.now();
        if (existing == null) {
            fresh.setLastSeenAt(now);
            authDeviceMapper.insert(fresh);
            return fresh;
        }
        LambdaUpdateWrapper<AuthDevice> uw = new LambdaUpdateWrapper<>();
        uw.eq(AuthDevice::getId, existing.getId())
                .set(AuthDevice::getPlatform, fresh.getPlatform())
                .set(AuthDevice::getLastSeenAt, now);
        applyIfPresent(uw, AuthDevice::getDeviceModel, fresh.getDeviceModel());
        applyIfPresent(uw, AuthDevice::getOsVersion, fresh.getOsVersion());
        applyIfPresent(uw, AuthDevice::getAppVersion, fresh.getAppVersion());
        applyIfPresent(uw, AuthDevice::getPushToken, fresh.getPushToken());
        applyIfPresent(uw, AuthDevice::getLastIp, fresh.getLastIp());
        authDeviceMapper.update(null, uw);

        // 重新读取保证 entity 一致 (避免回填 metaObjectHandler 字段缺失)。
        return authDeviceMapper.selectById(existing.getId());
    }

    public int touchLastSeen(Long id, String lastIp) {
        if (id == null) {
            return 0;
        }
        LambdaUpdateWrapper<AuthDevice> uw = new LambdaUpdateWrapper<>();
        uw.eq(AuthDevice::getId, id)
                .set(AuthDevice::getLastSeenAt, OffsetDateTime.now());
        applyIfPresent(uw, AuthDevice::getLastIp, lastIp);
        return authDeviceMapper.update(null, uw);
    }

    private static <T> void applyIfPresent(
            LambdaUpdateWrapper<AuthDevice> uw,
            com.baomidou.mybatisplus.core.toolkit.support.SFunction<AuthDevice, T> col,
            T value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        uw.set(col, value);
    }
}
