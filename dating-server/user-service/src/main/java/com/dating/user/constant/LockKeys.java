package com.dating.user.constant;

import java.time.Duration;

// Redisson 分布式锁 key (lock:<service>:...);ResolveOrCreate 三种入口加锁,
// 避免并发同 identifier 创建多条 user_info placeholder。
public final class LockKeys {

    private LockKeys() {}

    public static final String REGISTER_PHONE_PREFIX = "lock:user:register:phone:";
    public static final String REGISTER_THIRD_PARTY_PREFIX = "lock:user:register:thirdparty:";
    public static final String REGISTER_DEVICE_PREFIX = "lock:user:register:device:";

    // 等待获取锁的最长时间;超时即放弃,避免请求堆积
    public static final Duration WAIT = Duration.ofSeconds(3);
    // 锁租期;超过此时长自动释放,防止 holder 挂掉死锁
    public static final Duration LEASE = Duration.ofSeconds(30);

    public static String registerPhone(String phoneE164) {
        return REGISTER_PHONE_PREFIX + phoneE164;
    }

    public static String registerThirdParty(int platform, String thirdPartyUserId) {
        return REGISTER_THIRD_PARTY_PREFIX + platform + ":" + thirdPartyUserId;
    }

    public static String registerDevice(int platform, String deviceId) {
        return REGISTER_DEVICE_PREFIX + platform + ":" + deviceId;
    }
}
