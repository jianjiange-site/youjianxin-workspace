package com.dating.user.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.user.entity.UserThirdPartyRegistration;
import com.dating.user.mapper.UserThirdPartyRegistrationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

// user_third_party_registration 单表;(third_party_login_user_id, platform) partial unique where NOT deleted。
// 软删后允许同 (third_party, platform) 重新绑定。
@Component
@RequiredArgsConstructor
public class UserThirdPartyRegistrationManager {

    private final UserThirdPartyRegistrationMapper mapper;

    public Optional<UserThirdPartyRegistration> findActive(String thirdPartyUserId, Short platform) {
        if (thirdPartyUserId == null || platform == null) {
            return Optional.empty();
        }
        LambdaQueryWrapper<UserThirdPartyRegistration> qw = new LambdaQueryWrapper<>();
        // @TableLogic 已自动过滤 deleted=false,无需手写
        qw.eq(UserThirdPartyRegistration::getThirdPartyLoginUserId, thirdPartyUserId)
                .eq(UserThirdPartyRegistration::getPlatform, platform)
                .last("LIMIT 1");
        return Optional.ofNullable(mapper.selectOne(qw));
    }

    // 给 UserBanService 用:取该用户所有未软删的三方绑定,逐一过运营级封禁集合。
    public List<UserThirdPartyRegistration> findActiveByUserId(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<UserThirdPartyRegistration> qw = new LambdaQueryWrapper<>();
        qw.eq(UserThirdPartyRegistration::getUserId, userId);
        return mapper.selectList(qw);
    }

    public int insertBinding(Long userId, String thirdPartyUserId, Short platform, String googleEmail) {
        UserThirdPartyRegistration r = new UserThirdPartyRegistration();
        r.setUserId(userId);
        r.setThirdPartyLoginUserId(thirdPartyUserId);
        r.setPlatform(platform);
        r.setGoogleEmail(googleEmail);
        return mapper.insert(r);
    }
}
