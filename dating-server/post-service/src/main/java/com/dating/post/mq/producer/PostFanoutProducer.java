package com.dating.post.mq.producer;

import com.dating.post.mq.FanoutMessage;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Fanout 消息生产者(post-service-design §10.2.2)。
 * <p>
 * 发帖事务 COMMIT 后由 {@code PostWriteService} 同步调 {@link #send},
 * syncSend + 本地 3 次 retry。接受"事务提交但 MQ 发送失败"这个极小窗口:
 * <ul>
 *   <li>3 次重试都失败 → log.error + {@code post.fanout.produce.fail} +1,不阻塞返回</li>
 *   <li>5 分钟内 {@code FeedScoreJob} 重建全网热门池,该帖可从主力位曝光</li>
 *   <li>不上 Outbox(避免 PG 增表 / 增 Job)</li>
 * </ul>
 * 客户端内置的 {@code retry-times-when-send-failed} 已在配置里关掉,所有重试由本类掌控。
 */
@Component
@RequiredArgsConstructor
public class PostFanoutProducer {

    private static final Logger log = LoggerFactory.getLogger(PostFanoutProducer.class);

    /** 必带 {@code youjianxin_dating_} 前缀(CLAUDE.md 隔离前缀)。 */
    static final String TOPIC = "youjianxin_dating_post_fanout_v1";

    private static final int MAX_RETRY = 3;
    private static final long TIMEOUT_MS = 2000L;

    private final RocketMQTemplate template;
    private final MeterRegistry metrics;

    public void send(long postId, long authorUserId, long createdAtEpoch) {
        FanoutMessage payload = new FanoutMessage(postId, authorUserId, createdAtEpoch);
        Message<FanoutMessage> message = MessageBuilder.withPayload(payload).build();

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                SendResult result = template.syncSend(TOPIC, message, TIMEOUT_MS);
                if (result != null && SendStatus.SEND_OK == result.getSendStatus()) {
                    return;
                }
                log.warn("fanout send non-OK status, postId={} attempt={} status={}",
                        postId, attempt, result == null ? "null" : result.getSendStatus());
            } catch (Exception e) {
                log.warn("fanout send retry, postId={} attempt={}", postId, attempt, e);
            }
        }

        log.error("fanout send FAILED after {} retries, postId={}", MAX_RETRY, postId);
        metrics.counter("post.fanout.produce.fail").increment();
    }
}
