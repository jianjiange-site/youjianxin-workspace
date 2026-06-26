package com.dating.match.scheduler;

import com.dating.match.entity.DhInteractionTask;
import com.dating.match.manager.DhInteractionTaskManager;
import com.dating.match.service.DhTaskExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LikeVisitorTaskExecutor — 每 1 分钟扫到期任务,UPSERT like/visit_record + 硬删任务行。
 *
 * <p>详见 docs/match-service-prd-tech.md §6.3.3。
 *
 * <ul>
 *   <li>扫描:{@code SELECT * FROM dh_interaction_task WHERE execute_time <= NOW() ORDER BY execute_time LIMIT 1000}</li>
 *   <li>每条独立短事务执行(委派给 {@link DhTaskExecutorService} ── @Transactional 必须走 Spring 代理)</li>
 *   <li>action=LIKE → UPSERT like_record(source = DH_PLAN_ONLINE|OFFLINE, like_content)</li>
 *   <li>action=VISIT → UPSERT visit_record(source 同理, visit_count += 1)</li>
 *   <li>成功 → 硬删任务行(短生命周期表,不软删)</li>
 *   <li>积压监控:execute_time + 5min < now 的行数,打 WARN</li>
 *   <li>ShedLock 兜底多实例:同一时刻只一个 instance 在跑</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeVisitorTaskExecutor {

    private static final int BATCH_LIMIT = 1000;
    private static final long OVERDUE_WARN_THRESHOLD = 1000L;     // 积压超 1000 条打 WARN

    private final DhInteractionTaskManager taskManager;
    private final DhTaskExecutorService taskExecutorService;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "match-dh-executor", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void run() {
        long t0 = System.currentTimeMillis();
        List<DhInteractionTask> due = taskManager.scanDue(BATCH_LIMIT);
        if (due.isEmpty()) {
            checkBacklog();
            return;
        }

        int ok = 0, failed = 0;
        for (DhInteractionTask task : due) {
            try {
                taskExecutorService.executeOne(task);
                ok++;
            } catch (Exception e) {
                failed++;
                log.warn("dh-task execute failed id={} from={} to={} action={} scene={};留行下一轮重试",
                        task.getId(), task.getFromUserId(), task.getToUserId(),
                        task.getAction(), task.getScene(), e);
            }
        }
        log.info("LikeVisitorTaskExecutor done: due={} ok={} failed={} elapsedMs={}",
                due.size(), ok, failed, System.currentTimeMillis() - t0);
        checkBacklog();
    }

    private void checkBacklog() {
        try {
            long backlog = taskManager.countOverdue();
            if (backlog >= OVERDUE_WARN_THRESHOLD) {
                log.warn("dh-task backlog overdue >5min: count={} ── 查 DH 池规模 / DB 写入瓶颈 / executor 健康",
                        backlog);
            }
        } catch (Exception e) {
            log.debug("checkBacklog query failed", e);
        }
    }
}
