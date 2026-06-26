package com.dating.user.service.impl;

import com.dating.user.constant.LockKeys;
import com.dating.user.entity.UserDeviceRegistration;
import com.dating.user.entity.UserInfo;
import com.dating.user.entity.UserLoginPhone;
import com.dating.user.entity.UserThirdPartyRegistration;
import com.dating.user.exception.BizException;
import com.dating.user.exception.ErrorCodes;
import com.dating.user.manager.UserDeviceRegistrationManager;
import com.dating.user.manager.UserInfoManager;
import com.dating.user.manager.UserLoginPhoneManager;
import com.dating.user.manager.UserThirdPartyRegistrationManager;
import com.dating.user.service.UserIdentityService;
import com.dating.user.service.dto.ResolveOrCreateResult;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

// 身份解析三入口:libphonenumber 规范化 + Redisson 锁 (wait 3s / lease 30s) +
// 双重检查 (locked 后再 find,避免锁外 race 已建);命中 → touchLastOpenAt;未命中 → 事务建 placeholder + 绑定。
// 事务边界用 TransactionTemplate 显式启 (避免同类 self-invocation 失效)。
@Slf4j
@Service
public class UserIdentityServiceImpl implements UserIdentityService {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    private final UserInfoManager userInfoManager;
    private final UserLoginPhoneManager loginPhoneManager;
    private final UserThirdPartyRegistrationManager thirdPartyManager;
    private final UserDeviceRegistrationManager deviceManager;
    private final RedissonClient redissonClient;
    private final TransactionTemplate txTemplate;

    public UserIdentityServiceImpl(
            UserInfoManager userInfoManager,
            UserLoginPhoneManager loginPhoneManager,
            UserThirdPartyRegistrationManager thirdPartyManager,
            UserDeviceRegistrationManager deviceManager,
            RedissonClient redissonClient,
            PlatformTransactionManager transactionManager) {
        this.userInfoManager = userInfoManager;
        this.loginPhoneManager = loginPhoneManager;
        this.thirdPartyManager = thirdPartyManager;
        this.deviceManager = deviceManager;
        this.redissonClient = redissonClient;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ResolveOrCreateResult resolveByPhone(String phoneRaw, Short appName) {
        String e164 = normalizePhone(phoneRaw);
        Short app = appOr0(appName);

        return withLock(LockKeys.registerPhone(e164), () -> {
            Optional<UserLoginPhone> existed = loginPhoneManager.findByPhoneAndApp(e164, app);
            if (existed.isPresent()) {
                Long uid = existed.get().getUserId();
                userInfoManager.touchLastOpenAt(uid);
                UserInfo u = userInfoManager.getByUserId(uid);
                return new ResolveOrCreateResult(uid, isPending(u), false);
            }
            Long uid = txTemplate.execute(s -> {
                Long id = userInfoManager.insertPlaceholder(app, null);
                loginPhoneManager.insertBinding(id, e164, app);
                return id;
            });
            return new ResolveOrCreateResult(uid, true, true);
        });
    }

    @Override
    public ResolveOrCreateResult resolveByThirdParty(Short platform, String thirdPartyUserId,
                                                     Short appName, String googleEmail) {
        if (platform == null || platform <= 0) {
            throw new BizException(ErrorCodes.THIRD_PARTY_INVALID, "third party platform invalid");
        }
        if (thirdPartyUserId == null || thirdPartyUserId.isBlank()) {
            throw new BizException(ErrorCodes.THIRD_PARTY_INVALID, "third party user id blank");
        }
        Short app = appOr0(appName);

        return withLock(LockKeys.registerThirdParty(platform, thirdPartyUserId), () -> {
            Optional<UserThirdPartyRegistration> existed = thirdPartyManager.findActive(thirdPartyUserId, platform);
            if (existed.isPresent()) {
                Long uid = existed.get().getUserId();
                userInfoManager.touchLastOpenAt(uid);
                UserInfo u = userInfoManager.getByUserId(uid);
                return new ResolveOrCreateResult(uid, isPending(u), false);
            }
            Long uid = txTemplate.execute(s -> {
                Long id = userInfoManager.insertPlaceholder(app, null);
                thirdPartyManager.insertBinding(id, thirdPartyUserId, platform, googleEmail);
                return id;
            });
            return new ResolveOrCreateResult(uid, true, true);
        });
    }

    @Override
    public ResolveOrCreateResult resolveByDevice(String deviceId, Short platform, Short appName) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new BizException(ErrorCodes.DEVICE_ID_INVALID, "device id blank");
        }
        if (platform == null || platform <= 0) {
            throw new BizException(ErrorCodes.DEVICE_ID_INVALID, "device platform invalid");
        }
        Short app = appOr0(appName);

        return withLock(LockKeys.registerDevice(platform, deviceId), () -> {
            Optional<UserDeviceRegistration> existed = deviceManager.findActive(deviceId, platform, app);
            if (existed.isPresent()) {
                Long uid = existed.get().getUserId();
                userInfoManager.touchLastOpenAt(uid);
                UserInfo u = userInfoManager.getByUserId(uid);
                return new ResolveOrCreateResult(uid, isPending(u), false);
            }
            Long uid = txTemplate.execute(s -> {
                Long id = userInfoManager.insertPlaceholder(app, platform);
                deviceManager.insertBinding(id, deviceId, platform, app);
                return id;
            });
            return new ResolveOrCreateResult(uid, true, true);
        });
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCodes.PHONE_INVALID, "phone blank");
        }
        try {
            Phonenumber.PhoneNumber pn = PHONE_UTIL.parse(raw, null);
            if (!PHONE_UTIL.isValidNumber(pn)) {
                throw new BizException(ErrorCodes.PHONE_INVALID, "phone invalid: " + raw);
            }
            return PHONE_UTIL.format(pn, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            throw new BizException(ErrorCodes.PHONE_INVALID, "phone parse failed: " + raw);
        }
    }

    private Short appOr0(Short appName) {
        return appName == null ? 0 : appName;
    }

    private boolean isPending(UserInfo u) {
        return u == null || Boolean.TRUE.equals(u.getPending());
    }

    private <T> T withLock(String lockKey, Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(LockKeys.WAIT.toMillis(), LockKeys.LEASE.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BizException(ErrorCodes.TOO_MANY_REQUESTS, "register lock busy: " + lockKey);
            }
            return action.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCodes.SYSTEM_ERROR, "register lock interrupted");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
