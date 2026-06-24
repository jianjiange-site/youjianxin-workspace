package com.dating.post.mq.consumer;

import com.dating.post.client.UserClient;
import com.dating.post.config.CacheKeyConfig;
import com.dating.post.constant.RedisKeys;
import com.dating.post.mq.FanoutMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Fanout 消息消费者(post-service-design §10.2.2)。
 * <p>
 * {@link ConsumeMode#CONCURRENTLY} —— timeline 排序由 ZSet {@code score = epoch}
 * 决定,跟消费顺序无关;Consumer Group 天然按 queue 负载均衡,不需要 ShedLock。
 * <p>
 * 消费幂等:ZADD 同 key 同 member 同 score({@code createdAtEpoch} 是固定值)是
 * 覆盖语义,重投无副作用,不需要额外幂等表。
 * <p>
 * 失败模式:
 * <ul>
 *   <li>{@code userClient.getFriendUserIds} 抛 RPC 异常 → 抛出 → RocketMQ 自动重投(给 user-service 喘息时间)</li>
 *   <li>16 次重试仍失败 → 转 DLQ {@code %DLQ%youjianxin-dating-dev-post-service-fanout},人工排查</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "youjianxin-dating-dev-post-fanout-v1",
        consumerGroup = "youjianxin-dating-dev-post-service-fanout",
        consumeMode = ConsumeMode.CONCURRENTLY,
        accessKey = "${rocketmq.consumer.access-key}",
        secretKey = "${rocketmq.consumer.secret-key}"
)
public class PostFanoutConsumer implements RocketMQListener<FanoutMessage> {

    private static final Logger log = LoggerFactory.getLogger(PostFanoutConsumer.class);

    private static final Duration TIMELINE_TTL = Duration.ofDays(7);
    private static final int TIMELINE_CAP = 100;

    private final UserClient userClient;
    private final StringRedisTemplate redis;
    private final CacheKeyConfig cacheKeyConfig;
    private final MeterRegistry metrics;

    @Override
    public void onMessage(FanoutMessage msg) {
        Timer.Sample sample = Timer.start(metrics);
        try {
            List<Long> followers = userClient.getFriendUserIds(msg.authorUserId());
            if (followers == null || followers.isEmpty()) {
                return;
            }

            String prefix = cacheKeyConfig.getKeyPrefix();
            String member = String.valueOf(msg.postId());
            double score = msg.createdAtEpoch();

            for (Long follower : followers) {
                String key = RedisKeys.userTimeline(prefix, follower);
                redis.opsForZSet().add(key, member, score);
                Long size = redis.opsForZSet().zCard(key);
                if (size != null && size > TIMELINE_CAP) {
                    redis.opsForZSet().removeRange(key, 0, size - TIMELINE_CAP - 1);
                }
                redis.expire(key, TIMELINE_TTL);
            }

            log.info("Fanout complete: postId={} followers={}", msg.postId(), followers.size());
        } finally {
            sample.stop(metrics.timer("post.fanout.consume.latency"));
        }
    }
}
