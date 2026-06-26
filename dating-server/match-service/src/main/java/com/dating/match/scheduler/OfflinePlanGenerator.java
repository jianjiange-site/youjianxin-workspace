package com.dating.match.scheduler;

import com.dating.match.client.ImClient;
import com.dating.match.config.DhPlanConfig;
import com.dating.match.constant.CacheKeys;
import com.dating.match.constant.DhTaskScene;
import com.dating.match.manager.DhInteractionTaskManager;
import com.dating.match.service.DhPlanGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OfflinePlanGenerator — 每 20 分钟扫"离线满 20min"的真人,给他们排一波 OFFLINE 计划任务。
 *
 * <p>详见 docs/match-service-prd-tech.md §6.3.2。
 *
 * <ul>
 *   <li>选人:{@code im-service.ListRecentOfflineUsers(max(cursor, now-3h), now-20min, 5000)}
 *       —— 读 PG user_online_session(已收口 offline_at 落在窗口内的真人)</li>
 *   <li>游标:{@code match:dh_plan:cursor:offline} STRING(epoch ms),初始 now - 3h;
 *       本轮结束 → SET cursor = now - 20min(留 20 分钟重叠窗口,让长期离线的人能反复评估)</li>
 *   <li>过滤三道闸:lastScene != OFFLINE(单次离线期最多 1 个 OFFLINE 计划)/
 *       任务表 dedup(scene=OFFLINE)/ 类型闸(BH only)。<b>无 cooldown 检查</b>(只靠 lastScene)</li>
 *   <li>生成:rand(3,6) 张 DH 互动任务,60% VISIT / 40% LIKE,execute_time 在 [now, now+30min] 均匀随机</li>
 *   <li>收尾:批量 INSERT 任务行 → SET last_scene=OFFLINE(<b>不写 cooldown</b>);
 *       用户下次重新上线后被 OnlinePlanGenerator 把 last_scene 改回 ONLINE,
 *       再次离线满 20min 后才会被本 generator 再抓</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfflinePlanGenerator {

    private final DhPlanConfig dhPlanConfig;
    private final ImClient imClient;
    private final DhInteractionTaskManager taskManager;
    private final DhPlanGeneratorService generatorService;
    private final StringRedisTemplate redis;

    @Scheduled(fixedDelay = 1_200_000)
    @SchedulerLock(name = "match-dh-offline-sweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        long t0 = System.currentTimeMillis();
        long now = Instant.now().toEpochMilli();
        long lookbackMs = dhPlanConfig.getOfflineLookbackSeconds() * 1000L;
        long thresholdMs = dhPlanConfig.getOfflineThresholdSeconds() * 1000L;

        long cursor = readCursorOrInit(now - lookbackMs);
        // since 至少不能早于 now - 3h(防扫太远历史);until = now - 20min(刚下线的不抓)
        long since = Math.max(cursor, now - lookbackMs);
        long until = now - thresholdMs;
        if (until <= since) {
            log.debug("OfflinePlanGenerator empty window: since={} until={}", since, until);
            advanceCursor(now - thresholdMs);
            return;
        }

        // 1) 选人:本窗口内已收口下线的真人
        List<Long> offline = imClient.listRecentOfflineUsers(since, until, dhPlanConfig.getBhBatchLimit());
        if (offline.isEmpty()) {
            advanceCursor(now - thresholdMs);
            log.debug("OfflinePlanGenerator no offline in window: since={} until={}", since, until);
            return;
        }

        // 2) 过滤一:lastScene 闸(本次离线期内已经处理过的跳过)
        List<Long> afterLastScene = filterByLastScene(offline);
        if (afterLastScene.isEmpty()) {
            advanceCursor(now - thresholdMs);
            return;
        }

        // 3) 过滤二:任务表 dedup(已有未执行 OFFLINE 任务的 BH 跳过)
        Set<Long> existing = taskManager.findExistingToUserIdsByScene(afterLastScene, DhTaskScene.OFFLINE);
        List<Long> candidates = new ArrayList<>(afterLastScene.size());
        for (Long uid : afterLastScene) if (!existing.contains(uid)) candidates.add(uid);
        if (candidates.isEmpty()) {
            advanceCursor(now - thresholdMs);
            return;
        }

        // 4) 调公共生成服务
        List<Long> generated = generatorService.runPlan(DhTaskScene.OFFLINE, candidates);

        // 5) 收尾:成功用户只 SET last_scene=OFFLINE(不 cooldown)
        for (Long uid : generated) {
            redis.opsForValue().set(
                    CacheKeys.dhPlanLastScene(uid), DhTaskScene.LAST_SCENE_OFFLINE);
        }

        advanceCursor(now - thresholdMs);
        log.info("OfflinePlanGenerator done: offline={} afterLastScene={} afterDedup={} generated={} elapsedMs={}",
                offline.size(), afterLastScene.size(), candidates.size(), generated.size(),
                System.currentTimeMillis() - t0);
    }

    /** 首次启动 / Redis 丢失 → 初始化为传入的兜底值。 */
    private long readCursorOrInit(long initIfMissing) {
        String s = redis.opsForValue().get(CacheKeys.DH_PLAN_CURSOR_OFFLINE);
        if (s == null) {
            redis.opsForValue().set(CacheKeys.DH_PLAN_CURSOR_OFFLINE, String.valueOf(initIfMissing));
            return initIfMissing;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            log.warn("OfflinePlanGenerator cursor corrupted: {};reset", s);
            redis.opsForValue().set(CacheKeys.DH_PLAN_CURSOR_OFFLINE, String.valueOf(initIfMissing));
            return initIfMissing;
        }
    }

    /** 本轮结束 → cursor = now - 20min(留 20min 重叠窗口) */
    private void advanceCursor(long newCursorMs) {
        redis.opsForValue().set(CacheKeys.DH_PLAN_CURSOR_OFFLINE, String.valueOf(newCursorMs));
    }

    /** 过滤掉 last_scene == OFFLINE 的用户(本次离线期内已生成过 OFFLINE 计划) */
    private List<Long> filterByLastScene(List<Long> userIds) {
        List<Long> ok = new ArrayList<>(userIds.size());
        for (Long uid : userIds) {
            String last = redis.opsForValue().get(CacheKeys.dhPlanLastScene(uid));
            if (DhTaskScene.LAST_SCENE_OFFLINE.equals(last)) continue;
            ok.add(uid);
        }
        return ok;
    }
}
