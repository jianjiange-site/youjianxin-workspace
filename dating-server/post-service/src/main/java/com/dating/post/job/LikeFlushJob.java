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
 * 点赞计数刷盘(post-service-design §6.2 / §9.4)。
 * <p>
 * 每分钟跑一次:
 * <ol>
 *   <li>{@code SRANDMEMBER updated_set 100} 取一批待刷盘 post_id</li>
 *   <li>对每个 post_id 走 Lua「GET + SET 0」原子取走 Redis 累加值</li>
 *   <li>{@code UPDATE post_stats SET like_count = like_count + delta}</li>
 *   <li>{@code SREM updated_set} 这批已刷盘的 post_id</li>
 * </ol>
 * <p>
 * 关键约束:
 * <ul>
 *   <li><b>Lua 原子</b>:GET 和 SET 0 之间绝不让别的 INCR 插队(否则丢点赞)</li>
 *   <li><b>UPDATE += delta</b> 增量加法,Job 之间乱序、重叠都不影响结果</li>
 *   <li><b>ShedLock</b>:多实例部署时同名 Job 全集群只跑一份</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LikeFlushJob {

    private static final Logger log = LoggerFactory.getLogger(LikeFlushJob.class);
    private static final int BATCH = 100;

    private final PostStatManager postStatManager;

    @Scheduled(fixedRate = 60_000)
    @SchedulerLock(name = "post.likeFlush",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S")
    public void flush() {
        List<Long> postIds = postStatManager.randomUpdated(BATCH);
        if (postIds.isEmpty()) {
            return;
        }

        int processed = 0;
        for (Long postId : postIds) {
            long delta = postStatManager.getAndReset(postStatManager.likeIncrKey(postId));
            if (delta == 0) {
                continue;
            }
            int rows = postStatManager.incrLike(postId, (int) delta);
            if (rows == 0) {
                log.warn("LikeFlush hit non-existent post_stats row, postId={} delta={}", postId, delta);
            }
            processed++;
        }

        // 该批次都处理完了,从 updated_set 移除(残留 Redis 增量为 0 不影响下次)
        postStatManager.removeFromUpdatedSet(postIds);

        log.info("Like flush completed, batch={} processed={}", postIds.size(), processed);
    }
}
