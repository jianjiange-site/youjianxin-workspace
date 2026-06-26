package com.dating.user.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.user.entity.UserDeviceRegistration;
import com.dating.user.mapper.UserDeviceRegistrationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

// user_device_registration 单表;(device_id, platform, app_name) partial unique where NOT deleted。
// 快速登录绑定;同设备可在不同 App 各自绑定。
@Component
@RequiredArgsConstructor
public class UserDeviceRegistrationManager {

    private final UserDeviceRegistrationMapper mapper;

    public Optional<UserDeviceRegistration> findActive(String deviceId, Short platform, Short appName) {
        if (deviceId == null || platform == null || appName == null) {
            return Optional.empty();
        }
        LambdaQueryWrapper<UserDeviceRegistration> qw = new LambdaQueryWrapper<>();
        qw.eq(UserDeviceRegistration::getDeviceId, deviceId)
                .eq(UserDeviceRegistration::getPlatform, platform)
                .eq(UserDeviceRegistration::getAppName, appName)
                .last("LIMIT 1");
        return Optional.ofNullable(mapper.selectOne(qw));
    }

    public int insertBinding(Long userId, String deviceId, Short platform, Short appName) {
        UserDeviceRegistration r = new UserDeviceRegistration();
        r.setUserId(userId);
        r.setDeviceId(deviceId);
        r.setPlatform(platform);
        r.setAppName(appName == null ? 0 : appName);
        return mapper.insert(r);
    }
}
