package com.dating.im.manager;

import com.dating.im.constant.PresenceKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 在线状态的 Redis 访问层:维护在线用户集合 {@link PresenceKeys#ONLINE_ZSET}(ZSet,
 * member = userId,score = 上线 epoch 毫秒)。
 */
@Component
public class PresenceRedisManager {

    private static final Logger log = LoggerFactory.getLogger(PresenceRedisManager.class);
    private static final int RANGE_LIMIT_HARD_MAX = 50_000;

    private final StringRedisTemplate redis;

    public PresenceRedisManager(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * ZADD NX:仅当用户不在集合里才加入。返回是否为本次新加(即「首个上线」),
     * 已在线时返回 false 且不覆盖原 score。
     */
    public boolean markOnline(String userId, long tsMs) {
        Boolean added = redis.opsForZSet().addIfAbsent(PresenceKeys.ONLINE_ZSET, userId, tsMs);
        return Boolean.TRUE.equals(added);
    }

    /** 取该用户的上线时刻(epoch 毫秒);null 表示不在在线集合里。 */
    public Long onlineSince(String userId) {
        Double score = redis.opsForZSet().score(PresenceKeys.ONLINE_ZSET, userId);
        return score == null ? null : score.longValue();
    }

    /** 从在线集合移除。 */
    public void remove(String userId) {
        redis.opsForZSet().remove(PresenceKeys.ONLINE_ZSET, userId);
    }

    /** 当前在线人数(去重用户数)。 */
    public long onlineCount() {
        Long n = redis.opsForZSet().zCard(PresenceKeys.ONLINE_ZSET);
        return n == null ? 0L : n;
    }

    /** score(上线时刻)早于 {@code olderThanMs} 的在线成员,供孤儿兜底清扫用。 */
    public Set<String> findOnlineBefore(long olderThanMs) {
        Set<String> members = redis.opsForZSet().rangeByScore(PresenceKeys.ONLINE_ZSET, 0, olderThanMs);
        return members == null ? Collections.emptySet() : members;
    }

    /**
     * 取 score(上线 epoch ms)落在 [sinceMs, untilMs] 区间内、按 score 升序的前 {@code limit} 个 userId。
     * 供 ListOnlineUserIds RPC 用(im-proto v0.9.0)。
     *
     * <p>区间两端皆闭合 —— Spring Data Redis 的简单 {@code rangeByScore} 重载默认 inclusive,
     * 边界用户在两次相邻 sweep 中可能重复返回一次,由调用方(match-service)的 cooldown +
     * dh_interaction_task 去重兜底,落库层不再额外区分。
     *
     * <p>非数字 OpenIM userID(如运维占位的 {@code imAdmin})不会被返回 —— ZSet 里有但
     * {@code Long.parseLong} 失败的成员跳过,与 {@code PresenceService.parseUserId} 行为一致。
     *
     * @param limit 调用方期望上限,会被夹到 [1, {@value #RANGE_LIMIT_HARD_MAX}];≤0 走默认 5000
     */
    public List<Long> rangeByScore(long sinceMs, long untilMs, int limit) {
        int effectiveLimit = limit <= 0 ? 5_000 : Math.min(limit, RANGE_LIMIT_HARD_MAX);
        Set<String> members = redis.opsForZSet().rangeByScore(
                PresenceKeys.ONLINE_ZSET, sinceMs, untilMs, 0, effectiveLimit);
        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> result = new ArrayList<>(members.size());
        for (String m : members) {
            try {
                result.add(Long.parseLong(m));
            } catch (NumberFormatException e) {
                log.debug("skip non-numeric IM userId in presence range: {}", m);
            }
        }
        return result;
    }
}
