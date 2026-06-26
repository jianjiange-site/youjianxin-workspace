package com.dating.user.constant;

import java.time.Duration;

// Redis key 规范:<service>:<domain>:<id>。
// 写后失效遵循 cache aside;TTL 显式声明,禁止永久 key。
public final class CacheKeys {

    private CacheKeys() {}

    public static final String USER_PROFILE_PREFIX = "user:profile:";
    public static final Duration USER_PROFILE_TTL = Duration.ofMinutes(30);

    // 含头像短期 URL / 兴趣聚合的大对象缓存;头像 / 兴趣写后必须连带失效
    public static final String USER_PROFILE_BIG_PREFIX = "user:profile:big:";
    public static final Duration USER_PROFILE_BIG_TTL = Duration.ofMinutes(30);

    public static final String USER_INTEREST_PREFIX = "user:interest:";
    public static final Duration USER_INTEREST_TTL = Duration.ofMinutes(30);

    // 单用户封禁状态短缓存,避免封禁判定打 DB 热点
    public static final String USER_BAN_STATUS_PREFIX = "user:ban:status:";
    public static final Duration USER_BAN_STATUS_TTL = Duration.ofMinutes(5);

    // 运营级三方账号封禁集合 (Set<thirdPartyUserId>);永久 key,运营运维维护
    public static final String USER_BAN_OPERATIONAL_SET = "user:ban:thirdparty-set";

    public static String userProfile(long userId) {
        return USER_PROFILE_PREFIX + userId;
    }

    public static String userProfileBig(long userId) {
        return USER_PROFILE_BIG_PREFIX + userId;
    }

    public static String userInterest(long userId) {
        return USER_INTEREST_PREFIX + userId;
    }

    public static String userBanStatus(long userId) {
        return USER_BAN_STATUS_PREFIX + userId;
    }
}
