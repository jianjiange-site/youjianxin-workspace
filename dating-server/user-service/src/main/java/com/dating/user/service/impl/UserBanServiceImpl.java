package com.dating.user.service.impl;

import com.dating.user.constant.CacheKeys;
import com.dating.user.constant.RegulationStatusMapping;
import com.dating.user.entity.UserInfo;
import com.dating.user.entity.UserThirdPartyRegistration;
import com.dating.user.exception.BizException;
import com.dating.user.exception.ErrorCodes;
import com.dating.user.manager.UserInfoManager;
import com.dating.user.manager.UserThirdPartyRegistrationManager;
import com.dating.user.service.UserBanService;
import com.dating.user.service.dto.CheckBanResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dating.youjianxin.proto.user.BanReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

// CheckBan 三层判定:
//   1) user:ban:status:{id} 短缓存命中直接返回
//   2) DB regulation_status IN (2,5) → BANNED / SUSPENDED
//   3) 用户三方绑定 third_party_login_user_id 命中 user:ban:thirdparty-set → OPERATIONAL
// 写后只对 status 短缓存,thirdparty-set 永久 (由运营维护)。
@Slf4j
@Service
public class UserBanServiceImpl implements UserBanService {

    private final UserInfoManager userInfoManager;
    private final UserThirdPartyRegistrationManager thirdPartyManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper redisObjectMapper;

    public UserBanServiceImpl(
            UserInfoManager userInfoManager,
            UserThirdPartyRegistrationManager thirdPartyManager,
            RedisTemplate<String, Object> redisTemplate,
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper) {
        this.userInfoManager = userInfoManager;
        this.thirdPartyManager = thirdPartyManager;
        this.redisTemplate = redisTemplate;
        this.redisObjectMapper = redisObjectMapper;
    }

    @Override
    public CheckBanResult checkBan(long userId) {
        if (userId <= 0) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "userId must be positive");
        }

        String cacheKey = CacheKeys.userBanStatus(userId);
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof Map<?, ?> raw) {
            try {
                return redisObjectMapper.convertValue(raw, CheckBanResult.class);
            } catch (IllegalArgumentException e) {
                log.warn("user-service ban cache corrupted, evict key={}", cacheKey, e);
                redisTemplate.delete(cacheKey);
            }
        }

        UserInfo u = userInfoManager.getByUserId(userId);
        if (u == null) {
            throw new BizException(ErrorCodes.USER_NOT_FOUND, "user not found: " + userId);
        }

        CheckBanResult result = decide(u);
        redisTemplate.opsForValue().set(cacheKey, result, CacheKeys.USER_BAN_STATUS_TTL);
        return result;
    }

    private CheckBanResult decide(UserInfo u) {
        Short status = u.getRegulationStatus();
        long whenMs = u.getUpdatedAt() == null
                ? OffsetDateTime.now().toInstant().toEpochMilli()
                : u.getUpdatedAt().toInstant().toEpochMilli();

        if (RegulationStatusMapping.isBannedHard(status)) {
            return new CheckBanResult(true, BanReason.BAN_REASON_USER_BANNED, whenMs, "USER_BANNED");
        }
        if (RegulationStatusMapping.isSuspended(status)) {
            return new CheckBanResult(true, BanReason.BAN_REASON_USER_SUSPENDED, whenMs, "USER_SUSPENDED");
        }
        if (hitsOperationalSet(u.getUserId())) {
            return new CheckBanResult(true, BanReason.BAN_REASON_OPERATIONAL, whenMs, "OPERATIONAL_BANNED");
        }
        return CheckBanResult.notBanned();
    }

    private boolean hitsOperationalSet(Long userId) {
        List<UserThirdPartyRegistration> regs = thirdPartyManager.findActiveByUserId(userId);
        if (regs.isEmpty()) {
            return false;
        }
        SetOperations<String, Object> setOps = redisTemplate.opsForSet();
        for (UserThirdPartyRegistration r : regs) {
            Boolean hit = setOps.isMember(CacheKeys.USER_BAN_OPERATIONAL_SET, r.getThirdPartyLoginUserId());
            if (Boolean.TRUE.equals(hit)) {
                return true;
            }
        }
        return false;
    }
}
