package com.dating.post.service;

import com.dating.post.client.UserClient;
import com.dating.post.config.CacheKeyConfig;
import com.dating.post.constant.RedisKeys;
import com.dating.post.entity.Post;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 写扩散(post-service-design §10.2.2)。
 * <p>
 * 关注数小、读 Feed 要极快 → 选写扩散(push)。
 * 发帖时拿到关注者列表后逐个 ZADD 各人 timeline,**裁剪到最近 100 条**(防膨胀)。
 * <p>
 * 失败模式:user-service down → fanout no-op,5 分钟后全网池 ① 重建覆盖这条帖,
 * 用户仍能从主力位看到。
 */
@Service
@RequiredArgsConstructor
public class PostFanoutService {

    private static final Logger log = LoggerFactory.getLogger(PostFanoutService.class);

    private static final Duration TIMELINE_TTL = Duration.ofDays(7);
    private static final int TIMELINE_CAP = 100;

    private final UserClient userClient;
    private final StringRedisTemplate redis;
    private final CacheKeyConfig cacheKeyConfig;

    /**
     * 异步写扩散到关注者 timeline。
     * <p>
     * {@code @Async} 走 {@link com.dating.post.config.AsyncConfig} 的 fanoutExecutor;
     * 异常被 AsyncUncaughtExceptionHandler 兜底打 ERROR 日志。
     */
    @Async("fanoutExecutor")
    public void fanoutToFollowers(long postId, long authorUserId, OffsetDateTime createdAt) {
        List<Long> followers = userClient.getFriendUserIds(authorUserId);
        if (followers == null || followers.isEmpty()) {
            return;
        }

        String prefix = cacheKeyConfig.getKeyPrefix();
        double score = createdAt.toEpochSecond();
        String member = String.valueOf(postId);

        for (Long follower : followers) {
            String key = RedisKeys.userTimeline(prefix, follower);
            redis.opsForZSet().add(key, member, score);

            // 裁剪到最近 100 条,从 score 最低(最老)那端砍
            Long size = redis.opsForZSet().zCard(key);
            if (size != null && size > TIMELINE_CAP) {
                redis.opsForZSet().removeRange(key, 0, size - TIMELINE_CAP - 1);
            }
            redis.expire(key, TIMELINE_TTL);
        }

        log.info("Fanout complete: postId={} followers={}", postId, followers.size());
    }
}
