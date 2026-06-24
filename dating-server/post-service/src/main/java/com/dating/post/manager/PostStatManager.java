package com.dating.post.manager;

import com.dating.post.config.CacheKeyConfig;
import com.dating.post.constant.RedisKeys;
import com.dating.post.entity.PostStat;
import com.dating.post.mapper.PostStatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计数底座 + Redis 增量(post-service-design §6.2 写合并核心)。
 * <p>
 * 关键规则:
 * <ul>
 *   <li>**实时值 = post_stats.like_count + Redis incr**,读侧在内存里加</li>
 *   <li>{@link #getAndReset(String)} 走 Lua 原子「GET + SET 0」,绝不丢毫秒内新点赞</li>
 *   <li>刷盘走 `UPDATE += delta`(增量加法非覆盖),Job 之间乱序、重叠都不影响结果</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PostStatManager {

    private static final Duration INCR_TTL = Duration.ofDays(7);

    /** Lua: 原子取走 + 归零,期间新写回不会丢。 */
    private static final RedisScript<String> GET_AND_RESET = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]) "
                    + "if v == false then return '0' end "
                    + "redis.call('SET', KEYS[1], '0') "
                    + "return v",
            String.class
    );

    private final PostStatMapper postStatMapper;
    private final StringRedisTemplate redis;
    private final CacheKeyConfig cacheKeyConfig;

    /** 发帖时初始化计数底座(0, 0)。 */
    public void initStat(long postId) {
        PostStat stat = new PostStat();
        stat.setPostId(postId);
        stat.setLikeCount(0);
        stat.setCommentCount(0);
        postStatMapper.insert(stat);
    }

    /** 批量取底座(FeedScoreJob 用 selectBatchIds,单表)。 */
    public Map<Long, PostStat> batchGet(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<PostStat> stats = postStatMapper.selectBatchIds(postIds);
        Map<Long, PostStat> map = new HashMap<>(stats.size() * 2);
        for (PostStat s : stats) {
            map.put(s.getPostId(), s);
        }
        return map;
    }

    /** 点赞实时增量:+1 / -1。同时把 post_id 加入待刷盘集合。 */
    public void applyLikeDelta(long postId, int delta) {
        String prefix = cacheKeyConfig.getKeyPrefix();
        String incrKey = RedisKeys.postLikeIncr(prefix, postId);
        redis.opsForValue().increment(incrKey, delta);
        redis.expire(incrKey, INCR_TTL);
        redis.opsForSet().add(RedisKeys.postUpdatedSet(prefix), String.valueOf(postId));
    }

    /** 评论实时增量:+1 / -1。 */
    public void applyCommentDelta(long postId, int delta) {
        String prefix = cacheKeyConfig.getKeyPrefix();
        String incrKey = RedisKeys.postCommentIncr(prefix, postId);
        redis.opsForValue().increment(incrKey, delta);
        redis.expire(incrKey, INCR_TTL);
        redis.opsForSet().add(RedisKeys.postUpdatedSet(prefix), String.valueOf(postId));
    }

    /** 读侧:取 Redis 未刷盘增量(可能负)。 */
    public long readLikeIncr(long postId) {
        return parseLong(redis.opsForValue().get(
                RedisKeys.postLikeIncr(cacheKeyConfig.getKeyPrefix(), postId)));
    }

    public long readCommentIncr(long postId) {
        return parseLong(redis.opsForValue().get(
                RedisKeys.postCommentIncr(cacheKeyConfig.getKeyPrefix(), postId)));
    }

    /** 同时读两个增量(详情接口减一次往返)。 */
    public long[] readIncr(long postId) {
        String prefix = cacheKeyConfig.getKeyPrefix();
        List<String> values = redis.opsForValue().multiGet(List.of(
                RedisKeys.postLikeIncr(prefix, postId),
                RedisKeys.postCommentIncr(prefix, postId)
        ));
        return new long[]{
                parseLong(values == null ? null : values.get(0)),
                parseLong(values == null ? null : values.get(1))
        };
    }

    /** Lua 原子取走 + 归零(Job 刷盘用)。 */
    public long getAndReset(String incrKey) {
        String val = redis.execute(GET_AND_RESET, List.of(incrKey));
        return parseLong(val);
    }

    /** SREM 待刷盘集合中的一批 post_id。 */
    public void removeFromUpdatedSet(Collection<Long> postIds) {
        if (postIds.isEmpty()) {
            return;
        }
        Object[] members = postIds.stream().map(String::valueOf).toArray();
        redis.opsForSet().remove(updatedSetKey(), members);
    }

    /** 随机取一批待刷盘 post_id(Job 用 SRANDMEMBER 而不 SPOP,避免漏)。 */
    public List<Long> randomUpdated(int batchSize) {
        Set<String> raw = redis.opsForSet().distinctRandomMembers(updatedSetKey(), batchSize);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        return raw.stream().map(Long::parseLong).toList();
    }

    /** Job 用:加上一批增量(走 mapper 增量 UPDATE)。 */
    public int incrLike(long postId, int delta) {
        return postStatMapper.incrLikeCount(postId, delta);
    }

    public int incrComment(long postId, int delta) {
        return postStatMapper.incrCommentCount(postId, delta);
    }

    public String likeIncrKey(long postId) {
        return RedisKeys.postLikeIncr(cacheKeyConfig.getKeyPrefix(), postId);
    }

    public String commentIncrKey(long postId) {
        return RedisKeys.postCommentIncr(cacheKeyConfig.getKeyPrefix(), postId);
    }

    private String updatedSetKey() {
        return RedisKeys.postUpdatedSet(cacheKeyConfig.getKeyPrefix());
    }

    private static long parseLong(String s) {
        if (s == null || s.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
