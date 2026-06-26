package com.dating.user.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.user.entity.UserLoginPhone;
import com.dating.user.mapper.UserLoginPhoneMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

// user_login_phone 单表;(phone_e164, app_name) DB unique。
// 无 deleted 列(手机号绑定永久),无需缓存(登录读 DB,QPS 不高)。
@Component
@RequiredArgsConstructor
public class UserLoginPhoneManager {

    private final UserLoginPhoneMapper userLoginPhoneMapper;

    public Optional<UserLoginPhone> findByPhoneAndApp(String phoneE164, Short appName) {
        if (phoneE164 == null || appName == null) {
            return Optional.empty();
        }
        LambdaQueryWrapper<UserLoginPhone> qw = new LambdaQueryWrapper<>();
        qw.eq(UserLoginPhone::getPhoneE164, phoneE164)
                .eq(UserLoginPhone::getAppName, appName)
                .last("LIMIT 1");
        return Optional.ofNullable(userLoginPhoneMapper.selectOne(qw));
    }

    public int insertBinding(Long userId, String phoneE164, Short appName) {
        UserLoginPhone p = new UserLoginPhone();
        p.setUserId(userId);
        p.setPhoneE164(phoneE164);
        p.setAppName(appName);
        p.setVerifiedAt(OffsetDateTime.now());
        return userLoginPhoneMapper.insert(p);
    }
}
