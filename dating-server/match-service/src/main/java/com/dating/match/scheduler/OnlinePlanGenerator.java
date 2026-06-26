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
 * OnlinePlanGenerator — 每 1 分钟扫"本周期新上线"的真人,给他们排 ONLINE 计划任务。
 *
 * <p>详见 docs/match-service-prd-tech.md §6.3.1。
 *
 * <ul>
 *   <li>选人:{@code im-service.ListOnlineUserIds(cursor, now, 5000)} —— 读 ZSet im:presence:online,
 *       天然只回真人(DH 没 IM 登录)</li>
 *   <li>游标:{@code match:dh_plan:cursor:online} STRING(epoch ms),初始 now - 1min,
 *       最大回看 30min;超过 → 告警 + 重置 cursor = now - 1min</li>
 *   <li>过滤三道闸:cooldown(Redis EXISTS)/ 任务表 dedup(scene=ONLINE)/ 类型闸(BH only)</li>
 *   <li>生成:rand(5,10) 张 DH 互动任务,60% VISIT / 40% LIKE,execute_time 在 [now, now+30min] 均匀随机</li>
 *   <li>收尾:批量 INSERT 任务行 → SET cooldown 2h → SET last_scene=ONLINE</li>
 * </ul>
 *
 * <p>ShedLock 兜底多实例:同一时刻只一个 instance 在跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnlinePlanGenerator {

    private static final long INITIAL_LOOKBACK_MS = 60_000L;     // 首次启动游标:now - 1min

    private final DhPlanConfig dhPlanConfig;
    private final ImClient imClient;
    private final DhInteractionTaskManager taskManager;
    private final DhPlanGeneratorService generatorService;
    private final StringRedisTemplate redis;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "match-dh-online-sweep", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void run() {
        long t0 = System.currentTimeMillis();
        long now = Instant.now().toEpochMilli();
        long cursor = readCursorOrInit(now);
        long maxLookback = dhPlanConfig.getOnlineLookbackSeconds() * 1000L;
        if (now - cursor > maxLookback) {
            log.warn("OnlinePlanGenerator cursor too stale: lagMs={} maxLookbackMs={};reset cursor",
                    now - cursor, maxLookback);
            cursor = now - INITIAL_LOOKBACK_MS;
        }

        // 1) 选人:本窗口内新上线的真人
        List<Long> online = imClient.listOnlineUserIds(cursor, now, dhPlanConfig.getBhBatchLimit());
        if (online.isEmpty()) {
            advanceCursor(now);
            log.debug("OnlinePlanGenerator no online in window: cursorMs={} nowMs={}", cursor, now);
            return;
        }

        // 2) 过滤一:cooldown 闸(单 BH 2h 内已排过 ONLINE 计划的跳过)
        List<Long> afterCooldown = filterOutCooldown(online);
        if (afterCooldown.isEmpty()) {
            advanceCursor(now);
            return;
        }

        // 3) 过滤二:任务表 dedup(已有未执行 ONLINE 任务的 BH 跳过)
        Set<Long> existing = taskManager.findExistingToUserIdsByScene(afterCooldown, DhTaskScene.ONLINE);
        List<Long> candidates = new ArrayList<>(afterCooldown.size());
        for (Long uid : afterCooldown) if (!existing.contains(uid)) candidates.add(uid);
        if (candidates.isEmpty()) {
            advanceCursor(now);
            return;
        }

        // 4) 调公共生成服务(类型闸 + cap + 召回 + 写任务)
        List<Long> generated = generatorService.runPlan(DhTaskScene.ONLINE, candidates);

        // 5) 收尾:成功用户 SET cooldown(2h)+ SET last_scene=ONLINE
        for (Long uid : generated) {
            redis.opsForValue().set(
                    CacheKeys.dhPlanCooldown(uid), "1", CacheKeys.DH_PLAN_COOLDOWN_TTL);
            redis.opsForValue().set(
                    CacheKeys.dhPlanLastScene(uid), DhTaskScene.LAST_SCENE_ONLINE);
        }

        advanceCursor(now);
        log.info("OnlinePlanGenerator done: online={} afterCooldown={} afterDedup={} generated={} elapsedMs={}",
                online.size(), afterCooldown.size(), candidates.size(), generated.size(),
                System.currentTimeMillis() - t0);
    }

    /** 读游标,首次启动 / Redis 丢失 → 初始化为 now - 1min。 */
    private long readCursorOrInit(long now) {
        String s = redis.opsForValue().get(CacheKeys.DH_PLAN_CURSOR_ONLINE);
        if (s == null) {
            long init = now - INITIAL_LOOKBACK_MS;
            redis.opsForValue().set(CacheKeys.DH_PLAN_CURSOR_ONLINE, String.valueOf(init));
            return init;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            log.warn("OnlinePlanGenerator cursor corrupted: {};reset", s);
            long init = now - INITIAL_LOOKBACK_MS;
            redis.opsForValue().set(CacheKeys.DH_PLAN_CURSOR_ONLINE, String.valueOf(init));
            return init;
        }
    }

    /** 本轮结束 → cursor = now(下一轮拉 [now, now+1min] 内新上线) */
    private void advanceCursor(long now) {
        redis.opsForValue().set(CacheKeys.DH_PLAN_CURSOR_ONLINE, String.valueOf(now));
    }

    /** 批量过滤 cooldown(逐 key EXISTS;单 sweep 量级 5000 内可接受;高量可换 MGET) */
    private List<Long> filterOutCooldown(List<Long> userIds) {
        List<Long> ok = new ArrayList<>(userIds.size());
        for (Long uid : userIds) {
            Boolean hit = redis.hasKey(CacheKeys.dhPlanCooldown(uid));
            if (Boolean.TRUE.equals(hit)) continue;
            ok.add(uid);
        }
        return ok;
    }
}
