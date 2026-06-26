package com.dating.match.service;

import com.dating.match.constant.MatchSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DH 延迟匹配 —— Spring TaskScheduler 进程内调度,15s ~ 2min。
 *
 * <p>详见 docs §5.2:不建 PG 表 / 不跑扫表 cron;接受服务重启丢 in-flight 任务。
 *
 * <p>Super Hi + DH 走 5.3 立即 createMatch 流程,不进本服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DhDelayedMatchService {

    private static final long MIN_DELAY_MS = 15_000L;
    private static final long MAX_DELAY_MS_EXCLUSIVE = 120_001L;

    private final TaskScheduler taskScheduler;
    private final MatchService matchService;

    /**
     * 调度一次延迟匹配回调。
     * 即使 caller 的 swipe 事务回滚也不会撤回(调用方需保证此方法在 AFTER_COMMIT 调用)。
     */
    public void scheduleDelayedMatch(long bhUserId, long dhUserId) {
        long delayMs = ThreadLocalRandom.current().nextLong(MIN_DELAY_MS, MAX_DELAY_MS_EXCLUSIVE);
        Instant fireAt = Instant.now().plusMillis(delayMs);
        taskScheduler.schedule(() -> doMatch(bhUserId, dhUserId, delayMs), fireAt);
        log.debug("DH delayed match scheduled bh={} dh={} fireInMs={}", bhUserId, dhUserId, delayMs);
    }

    private void doMatch(long bhUserId, long dhUserId, long actualDelayMs) {
        try {
            long matchId = matchService.createMatch(bhUserId, dhUserId, MatchSource.SWIPE_MATCH).getId();
            log.info("DH delayed match fired: bh={} dh={} delayMs={} matchId={}",
                    bhUserId, dhUserId, actualDelayMs, matchId);
        } catch (Exception e) {
            log.error("DH delayed match callback failed bh={} dh={}", bhUserId, dhUserId, e);
        }
    }
}
