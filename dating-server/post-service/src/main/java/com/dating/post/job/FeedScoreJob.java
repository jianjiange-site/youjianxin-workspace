package com.dating.post.job;

import com.dating.post.service.FeedService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 全网热门池重建(post-service-design §10.2.1)。
 * <p>
 * 每 5 分钟全量重建:近 3 天所有 status=1 帖按 Hacker News 变体打分,
 * 性别分桶,影子写 tmp → RENAME 原子切换。
 * <p>
 * 为什么不发帖/点赞时实时改分:
 * 公式有时间衰减项 {@code (hoursDiff + 2)^1.5},每条帖的分数即使没人点赞也随时间被动跌,
 * 实时维护要跟着秒级时钟跑,得不偿失。5 分钟延迟用户感知不到「第 12 名 vs 第 10 名」。
 */
@Component
@RequiredArgsConstructor
public class FeedScoreJob {

    private static final Logger log = LoggerFactory.getLogger(FeedScoreJob.class);

    private final FeedService feedService;

    @Scheduled(fixedRate = 5 * 60_000L, initialDelay = 60_000)
    @SchedulerLock(name = "post.feedScore",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT30S")
    public void rebuild() {
        long start = System.currentTimeMillis();
        try {
            feedService.rebuildRecommendPool();
        } catch (Exception e) {
            log.error("Feed pool rebuild failed", e);
            return;
        }
        log.info("Feed pool rebuild done, durationMs={}", System.currentTimeMillis() - start);
    }
}
