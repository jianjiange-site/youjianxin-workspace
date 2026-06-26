package com.dating.match.constant;

import java.time.Duration;

/**
 * Redisson 分布式锁 key 规范:lock:<service>:...
 *
 * <p>详见 dating-server/docs/match-service-prd-tech.md §6.3 / §6.6:
 * <ul>
 *   <li>swipe:<userId>:<targetId>:单次 swipe 串行化(短租期 5s)</li>
 *   <li>d1:<yyyymmdd>:D1 cron 分布式锁(多实例只 1 个跑,长租期 1h)</li>
 * </ul>
 */
public final class LockKeys {

    private LockKeys() {}

    public static final String SWIPE_PREFIX = "lock:match:swipe:";
    public static final Duration SWIPE_WAIT = Duration.ofSeconds(5);
    public static final Duration SWIPE_LEASE = Duration.ofSeconds(3);

    public static final String D1_CRON_PREFIX = "lock:match:d1:";
    public static final Duration D1_CRON_LEASE = Duration.ofHours(1);

    public static String swipe(long userId, long targetUserId) {
        return SWIPE_PREFIX + userId + ":" + targetUserId;
    }

    public static String d1Cron(String yyyyMmDd) {
        return D1_CRON_PREFIX + yyyyMmDd;
    }
}
