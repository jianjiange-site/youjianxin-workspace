package com.dating.post.job;

import com.dating.post.manager.PostStatManager;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 评论计数刷盘(post-service-design §6.2)。
 * <p>
 * 逻辑与 {@link LikeFlushJob} 完全对称,只是操作的是 comment 增量字段。
 * 与 LikeFlushJob 共用同一个 {@code updated_set}:Job 移除时按 batch 全删,
 * 漏掉的下一轮 SRANDMEMBER 再捞起,不会丢数据。
 */
@Component
@RequiredArgsConstructor
public class CommentFlushJob {

    private static final Logger log = LoggerFactory.getLogger(CommentFlushJob.class);
    private static final int BATCH = 100;

    private final PostStatManager postStatManager;

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    @SchedulerLock(name = "post.commentFlush",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S")
    public void flush() {
        List<Long> postIds = postStatManager.randomUpdated(BATCH);
        if (postIds.isEmpty()) {
            return;
        }

        int processed = 0;
        for (Long postId : postIds) {
            long delta = postStatManager.getAndReset(postStatManager.commentIncrKey(postId));
            if (delta == 0) {
                continue;
            }
            int rows = postStatManager.incrComment(postId, (int) delta);
            if (rows == 0) {
                log.warn("CommentFlush hit non-existent post_stats row, postId={} delta={}", postId, delta);
            }
            processed++;
        }

        postStatManager.removeFromUpdatedSet(postIds);
        log.info("Comment flush completed, batch={} processed={}", postIds.size(), processed);
    }
}
