package com.dating.match.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.match.constant.CacheKeys;
import com.dating.match.entity.UserSwipeHistory;
import com.dating.match.mapper.UserSwipeHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * user_swipe_history 包装:写入 + 双源(PG + Redis SET)同步。
 *
 * <p>match:swiped:&lt;uid&gt; Redis SET 由 swipe 写入时 SADD 维护,
 * 用于 GetTodayFeed LPOP 后的二次过滤(见 FeedService)。
 * Redis 丢失时 lazy 由 PG 重建(本类提供 lazyHydrateSwipedSet)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwipeHistoryManager {

    private final UserSwipeHistoryMapper mapper;
    private final StringRedisTemplate redis;

    public boolean exists(long userId, long targetUserId) {
        return mapper.selectCount(new LambdaQueryWrapper<UserSwipeHistory>()
                .eq(UserSwipeHistory::getUserId, userId)
                .eq(UserSwipeHistory::getTargetUserId, targetUserId)) > 0;
    }

    public UserSwipeHistory findOne(long userId, long targetUserId) {
        return mapper.selectOne(new LambdaQueryWrapper<UserSwipeHistory>()
                .eq(UserSwipeHistory::getUserId, userId)
                .eq(UserSwipeHistory::getTargetUserId, targetUserId)
                .last("LIMIT 1"));
    }

    /** target 是否曾经右划过 caller(BH 互划立即匹配判定) */
    public boolean targetHasRightSwipedCaller(long callerUserId, long targetUserId) {
        return mapper.existsRightSwipeFromTarget(callerUserId, targetUserId);
    }

    /** 写入 swipe + 同步 SADD Redis swiped SET。事务由调用方控制。 */
    public UserSwipeHistory insert(long userId, long targetUserId, short targetUserType, short direction) {
        UserSwipeHistory row = new UserSwipeHistory();
        row.setUserId(userId);
        row.setTargetUserId(targetUserId);
        row.setTargetUserType(targetUserType);
        row.setDirection(direction);
        // swiped_at / created_at / updated_at 由 DB DEFAULT / MybatisMetaObjectHandler 填
        mapper.insert(row);
        try {
            redis.opsForSet().add(CacheKeys.swipedSet(userId), String.valueOf(targetUserId));
        } catch (Exception e) {
            log.warn("SADD match:swiped failed userId={} targetUserId={}",
                    userId, targetUserId, e);
        }
        return row;
    }

    /** 返回该用户历史 swipe 的所有 target_user_id(召回 exclude 用) */
    public List<Long> allTargetIds(long userId) {
        return mapper.selectTargetIdsByUser(userId);
    }

    /** Redis swiped SET 丢失时,由 PG 重建 */
    public long lazyHydrateSwipedSet(long userId) {
        List<Long> ids = allTargetIds(userId);
        if (ids.isEmpty()) return 0L;
        String[] members = ids.stream().map(String::valueOf).toArray(String[]::new);
        Long added = redis.opsForSet().add(CacheKeys.swipedSet(userId), members);
        return added == null ? 0L : added;
    }
}
