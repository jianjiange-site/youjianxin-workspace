package com.dating.match.scheduler;

import com.dating.match.mapper.UserSwipeHistoryMapper;
import com.dating.match.service.D1FeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * D1 队列离线生成 cron。
 *
 * <p>详见 dating-server/docs/match-service-prd-tech.md §4.2 + §6.5。
 *
 * <ul>
 *   <li>cron {@code 0 0 7 * * *} UTC = 美东 EDT 03:00 / EST 02:00(接受 1h 漂移,docs §6.5 备注)</li>
 *   <li>ShedLock 防多实例并行</li>
 *   <li>扫"昨天有过任何 swipe"的用户(active scope),逐个调 D1FeedService 重写 feed LIST</li>
 *   <li>单次处理上限 {@code match.d1.batch-limit}(默认 10000),超过留下一日 cron;
 *       真正的批处理拆分留 Phase 3+ 迭代</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class D1QueueScheduler {

    @Value("${match.d1.batch-limit:10000}")
    private int batchLimit;

    private final UserSwipeHistoryMapper swipeMapper;
    private final D1FeedService d1FeedService;

    @Scheduled(cron = "0 0 7 * * *", zone = "UTC")
    @SchedulerLock(name = "match-d1-queue", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void run() {
        long t0 = System.currentTimeMillis();
        OffsetDateTime yesterdayStart = OffsetDateTime.now(ZoneOffset.UTC)
                .minusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<Long> activeUserIds = swipeMapper.selectActiveUserIdsSince(yesterdayStart, batchLimit);
        log.info("D1QueueScheduler start: activeUsers={} sinceUtc={}", activeUserIds.size(), yesterdayStart);

        int ok = 0, skipped = 0, failed = 0;
        for (Long uid : activeUserIds) {
            try {
                int pushed = d1FeedService.generateForUser(uid);
                if (pushed > 0) ok++; else skipped++;
            } catch (Exception e) {
                failed++;
                log.warn("D1 generateForUser failed userId={};continue", uid, e);
            }
        }
        log.info("D1QueueScheduler done: ok={} skipped={} failed={} elapsedMs={}",
                ok, skipped, failed, System.currentTimeMillis() - t0);
    }
}
