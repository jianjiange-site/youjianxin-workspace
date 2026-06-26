package com.dating.user.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.user.constant.CacheKeys;
import com.dating.user.entity.UserInterest;
import com.dating.user.mapper.UserInterestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

// user_interest:全量替换 (DELETE + INSERT 同事务) + JSON 缓存 user:interest:{id}。
// 业务侧 service 层做计数校验 (图≤9 / 文字≤50);manager 只负责落库 + 失效缓存。
@Slf4j
@Component
@RequiredArgsConstructor
public class UserInterestManager {

    private final UserInterestMapper userInterestMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @SuppressWarnings("unchecked")
    public List<UserInterest> findByUserId(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        String key = CacheKeys.userInterest(userId);
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof List<?> list) {
            return (List<UserInterest>) list;
        }
        LambdaQueryWrapper<UserInterest> qw = new LambdaQueryWrapper<>();
        qw.eq(UserInterest::getUserId, userId).orderByAsc(UserInterest::getId);
        List<UserInterest> db = userInterestMapper.selectList(qw);
        redisTemplate.opsForValue().set(key, db, CacheKeys.USER_INTEREST_TTL);
        return db;
    }

    // 批量场景:单条 IN 查询,不打缓存(BatchGetProfile 用)。
    public List<UserInterest> findByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<UserInterest> qw = new LambdaQueryWrapper<>();
        qw.in(UserInterest::getUserId, userIds).orderByAsc(UserInterest::getUserId).orderByAsc(UserInterest::getId);
        return userInterestMapper.selectList(qw);
    }

    @Transactional(rollbackFor = Exception.class)
    public int replaceAll(Long userId, List<UserInterest> newList) {
        if (userId == null) {
            throw new IllegalArgumentException("userId required for replaceAll");
        }
        LambdaQueryWrapper<UserInterest> del = new LambdaQueryWrapper<>();
        del.eq(UserInterest::getUserId, userId);
        userInterestMapper.delete(del);

        int saved = 0;
        if (newList != null) {
            for (UserInterest item : newList) {
                if (item == null) continue;
                item.setUserId(userId);
                item.setId(null);
                userInterestMapper.insert(item);
                saved++;
            }
        }
        evict(userId);
        return saved;
    }

    public void evict(Long userId) {
        if (userId != null) {
            redisTemplate.delete(CacheKeys.userInterest(userId));
        }
    }
}
