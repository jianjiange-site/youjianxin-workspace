package com.dating.match.constant;

import java.time.Duration;

/**
 * Redis key 规范:<service>:<domain>:<id>。
 *
 * <p>详见 dating-server/docs/match-service-prd-tech.md §6.3。
 * <ul>
 *   <li>quota:<uid>:<yyyymmdd> HASH 36h:right_swipe / cards / super_hi 三计数</li>
 *   <li>feed:<uid> LIST 7d:RPUSH 入队 LPOP 弹出消费;空了自动重建</li>
 *   <li>swiped:<uid> SET 永久(白名单):消费阶段 SMISMEMBER 二次过滤已 swipe 的 target</li>
 *   <li>pref:<uid> HASH 24h:D1 偏好画像缓存,D1 cron 生成后写</li>
 * </ul>
 */
public final class CacheKeys {

    private CacheKeys() {}

    public static final String QUOTA_PREFIX = "match:quota:";
    public static final Duration QUOTA_TTL = Duration.ofHours(36);

    public static final String FEED_LIST_PREFIX = "match:feed:";
    public static final Duration FEED_LIST_TTL = Duration.ofDays(7);

    /** 永久(白名单);丢失时启动 lazy 由 user_swipe_history 重建,见 docs §6.3 */
    public static final String SWIPED_SET_PREFIX = "match:swiped:";

    public static final String PREF_HASH_PREFIX = "match:pref:";
    public static final Duration PREF_HASH_TTL = Duration.ofHours(24);

    // ── DH 模拟计划(docs §6.3 / §7.3)──
    /** OnlinePlanGenerator 游标:STRING epoch ms;初始 now-1min,最大回看 30min */
    public static final String DH_PLAN_CURSOR_ONLINE = "match:dh_plan:cursor:online";

    /** OfflinePlanGenerator 游标:STRING epoch ms;初始 now-3h,留 20min 重叠窗口 */
    public static final String DH_PLAN_CURSOR_OFFLINE = "match:dh_plan:cursor:offline";

    /** ONLINE 计划 cooldown:STRING 占位,TTL 2h(防同用户短时间内反复生成 ONLINE) */
    public static final String DH_PLAN_COOLDOWN_PREFIX = "match:dh_plan:cooldown:";
    public static final Duration DH_PLAN_COOLDOWN_TTL = Duration.ofHours(2);

    /** 该用户最近一次被哪类 generator 处理:STRING "ONLINE" / "OFFLINE",永久 */
    public static final String DH_PLAN_LAST_SCENE_PREFIX = "match:dh_plan:last_scene:";

    /** Feed LIST 内每个元素:"<target_user_id>:<target_user_type>" */
    public static String feedListMember(long targetUserId, short targetUserType) {
        return targetUserId + ":" + targetUserType;
    }

    public static String quota(long userId, String yyyyMmDd) {
        return QUOTA_PREFIX + userId + ":" + yyyyMmDd;
    }

    public static String feedList(long userId) {
        return FEED_LIST_PREFIX + userId;
    }

    public static String swipedSet(long userId) {
        return SWIPED_SET_PREFIX + userId;
    }

    public static String prefHash(long userId) {
        return PREF_HASH_PREFIX + userId;
    }

    public static String dhPlanCooldown(long userId) {
        return DH_PLAN_COOLDOWN_PREFIX + userId;
    }

    public static String dhPlanLastScene(long userId) {
        return DH_PLAN_LAST_SCENE_PREFIX + userId;
    }
}
