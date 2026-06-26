package com.dating.match.service;

import com.dating.match.config.DhPlanConfig;
import com.dating.match.constant.DhTaskAction;
import com.dating.match.entity.DhInteractionTask;
import com.dating.match.manager.DhInteractionTaskManager;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.VisitRecordManager;
import com.dating.match.client.UserClient;
import com.dating.youjianxin.proto.user.Candidate;
import com.dating.youjianxin.proto.user.Gender;
import com.dating.youjianxin.proto.user.UserProfile;
import com.dating.youjianxin.proto.user.UserType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DH 模拟计划公共生成逻辑(ONLINE / OFFLINE 共用)。
 *
 * <p>调用方(OnlinePlanGenerator / OfflinePlanGenerator)先做 scene 特异性过滤
 * (cooldown / lastScene)+ 任务表 dedup,再把通过的 BH 列表丢进来:
 * <ol>
 *   <li>批量拉 profile,按 BH/gender 过滤</li>
 *   <li>每 BH:24h cap 检查 → 决定本轮生成多少 LIKE / VISIT</li>
 *   <li>每 BH:取 exclude(like/visit/match 全 UNION)→ 调 user-service.listDhCandidates 取候选池</li>
 *   <li>随机抽 N 张 DH → 写 dh_interaction_task 行(execute_time 在 [now, now+window] 随机分布)</li>
 * </ol>
 *
 * <p>详见 docs §6.3 / §6.4。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DhPlanGeneratorService {

    /** 召回宽过滤:L2 等价(age ±10 / beauty ±25 / 不限人种),保证 candidate 充足 */
    private static final int AGE_HALF_WINDOW = 10;
    private static final int BEAUTY_HALF_WINDOW = 25;
    private static final int CAP_LOOKBACK_HOURS = 24;

    private final DhPlanConfig dhPlanConfig;
    private final UserClient userClient;
    private final LikeRecordManager likeRecordManager;
    private final VisitRecordManager visitRecordManager;
    private final MatchManager matchManager;
    private final DhInteractionTaskManager taskManager;

    /**
     * 给一批 BH 跑生成。
     *
     * @return 实际生成了至少一条任务行的 BH user_id 子集;调用方据此 SET cooldown / last_scene。
     */
    public List<Long> runPlan(short scene, List<Long> candidateBhUserIds) {
        if (candidateBhUserIds == null || candidateBhUserIds.isEmpty()) return Collections.emptyList();

        // 1) 批量拉 profile(单次 batchGetProfile 上限 200,大批量自行分片)
        List<UserProfile> profiles = batchProfiles(candidateBhUserIds);
        if (profiles.isEmpty()) return Collections.emptyList();

        DhPlanConfig.IntRange range = dhPlanConfig.countRangeForScene(scene == 1);
        int windowMin = dhPlanConfig.executeWindowMinForScene(scene == 1);

        List<Long> generated = new ArrayList<>();
        for (UserProfile p : profiles) {
            try {
                if (generateForBh(p, scene, range, windowMin)) {
                    generated.add(p.getUserId());
                }
            } catch (Exception e) {
                log.warn("dh-plan generateForBh failed bh={} scene={}",
                        p.getUserId(), scene, e);
            }
        }
        return generated;
    }

    /**
     * 给单个 BH 生成本轮 DH 互动任务;返回是否实际写入了任务行。
     */
    private boolean generateForBh(UserProfile bh, short scene,
                                  DhPlanConfig.IntRange range, int windowMin) {
        long bhId = bh.getUserId();

        // ── 类型闸:DH 不接收互动;im-service 接口理论上不会返回 DH,这里再兜底一次
        if (bh.getUserType() != UserType.USER_TYPE_BH) {
            log.debug("dh-plan skip non-BH user={} type={}", bhId, bh.getUserType());
            return false;
        }

        // ── 24h cap 检查:like / visit 各自剩余配额
        long usedLikes = likeRecordManager.countRecentDhLikes(bhId, CAP_LOOKBACK_HOURS);
        long usedVisits = visitRecordManager.countRecentDhVisits(bhId, CAP_LOOKBACK_HOURS);
        int remLike = Math.max(0, dhPlanConfig.getDailyDhLikeCap() - (int) usedLikes);
        int remVisit = Math.max(0, dhPlanConfig.getDailyDhVisitCap() - (int) usedVisits);
        if (remLike == 0 && remVisit == 0) {
            log.debug("dh-plan skip bh={} both caps reached (likes={}, visits={})",
                    bhId, usedLikes, usedVisits);
            return false;
        }

        // ── 决定本轮 LIKE / VISIT 数
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int targetCount = randomInRange(rnd, range.getMin(), range.getMax());
        int targetLike = (int) Math.round(targetCount * (1.0 - dhPlanConfig.getVisitRatio()));
        int targetVisit = targetCount - targetLike;
        int finalLike = Math.min(targetLike, remLike);
        int finalVisit = Math.min(targetVisit, remVisit);
        int needDh = finalLike + finalVisit;
        if (needDh == 0) {
            log.debug("dh-plan skip bh={} after cap clamp (target={} likes={} visits={})",
                    bhId, targetCount, finalLike, finalVisit);
            return false;
        }

        // ── exclude_user_ids:已 like / visit 过的 DH + 已配对的 partner
        Set<Long> exclude = collectExcludeUserIds(bhId);
        // 自己也排除,理论上召回过滤已带,这里防御
        exclude.add(bhId);

        // ── 召回 DH 候选池
        Gender targetGender = (bh.getGender() == Gender.GENDER_MALE)
                ? Gender.GENDER_FEMALE : Gender.GENDER_MALE;
        int age = Math.max(18, bh.getAge());
        int beauty = clamp(bh.getBeautyScore(), 0, 100);
        List<Candidate> pool = userClient.listDhCandidates(
                targetGender,
                Math.max(18, age - AGE_HALF_WINDOW),
                Math.min(99, age + AGE_HALF_WINDOW),
                Math.max(0, beauty - BEAUTY_HALF_WINDOW),
                Math.min(100, beauty + BEAUTY_HALF_WINDOW),
                null,                                                // 不限人种(L2 等价)
                new ArrayList<>(exclude),
                dhPlanConfig.getCandidatePoolSize());
        if (pool.isEmpty()) {
            log.debug("dh-plan skip bh={} listDhCandidates returned empty pool", bhId);
            return false;
        }

        // ── 候选不够 → 缩到能给的数量,LIKE/VISIT 比例尽量保持
        if (pool.size() < needDh) {
            int shrink = needDh - pool.size();
            int shrinkVisit = Math.min(shrink, finalVisit);
            finalVisit -= shrinkVisit;
            int leftover = shrink - shrinkVisit;
            finalLike = Math.max(0, finalLike - leftover);
            needDh = finalLike + finalVisit;
            if (needDh == 0) return false;
        }

        // ── 随机洗牌后切前 needDh 个 DH,前 finalLike 个跑 LIKE,剩下跑 VISIT
        List<Candidate> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, rnd);
        List<Candidate> picked = shuffled.subList(0, needDh);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long windowMs = windowMin * 60_000L;
        int wrote = 0;
        for (int i = 0; i < picked.size(); i++) {
            Candidate dh = picked.get(i);
            boolean isLike = i < finalLike;
            short action = isLike ? DhTaskAction.LIKE : DhTaskAction.VISIT;
            String content = isLike ? pickTemplate(rnd, bh.getGender()) : null;
            OffsetDateTime executeAt = now.plus(java.time.Duration.ofMillis(rnd.nextLong(windowMs + 1)));

            DhInteractionTask task = new DhInteractionTask();
            task.setFromUserId(dh.getUserId());
            task.setToUserId(bhId);
            task.setAction(action);
            task.setScene(scene);
            task.setExecuteTime(executeAt);
            task.setLikeContent(content);
            taskManager.insert(task);
            wrote++;
        }
        log.info("dh-plan wrote bh={} scene={} likes={} visits={} (pool={}, exclude={})",
                bhId, scene, finalLike, finalVisit, pool.size(), exclude.size());
        return wrote > 0;
    }

    /**
     * UNION 三路 exclude_user_ids(docs §6.4 防穿帮 #2)。
     * 单 BH 量级在百条以内,无需 SQL UNION 一次性查;三次单表查询拼内存集合更可读、命中各自单表索引。
     */
    private Set<Long> collectExcludeUserIds(long bhId) {
        Set<Long> exclude = new HashSet<>();
        exclude.addAll(likeRecordManager.likedFromIdsOf(bhId));
        exclude.addAll(visitRecordManager.visitedFromIdsOf(bhId));
        // listFriendUserIds 已经做了 CASE WHEN low/high 反查,直接拿到 partner 列表
        exclude.addAll(matchManager.listFriendUserIds(bhId, 1000));
        return exclude;
    }

    /**
     * 按目标 BH 的 gender 过滤 templates,优先精确匹配,fallback 到 ANY,再 fallback 到全集。
     */
    private String pickTemplate(ThreadLocalRandom rnd, Gender bhGender) {
        List<DhPlanConfig.TemplateEntry> all = dhPlanConfig.getLikeContentTemplates();
        if (all == null || all.isEmpty()) return null;

        String genderTag = switch (bhGender) {
            case GENDER_MALE -> "MALE";
            case GENDER_FEMALE -> "FEMALE";
            default -> "ANY";
        };
        List<DhPlanConfig.TemplateEntry> matched = new ArrayList<>();
        for (DhPlanConfig.TemplateEntry t : all) {
            String pref = t.getGenderPref() == null ? "ANY" : t.getGenderPref().toUpperCase();
            if (pref.equals(genderTag) || "ANY".equals(pref)) matched.add(t);
        }
        List<DhPlanConfig.TemplateEntry> use = matched.isEmpty() ? all : matched;
        DhPlanConfig.TemplateEntry pick = use.get(rnd.nextInt(use.size()));
        return pick.getContent();
    }

    private List<UserProfile> batchProfiles(List<Long> userIds) {
        List<UserProfile> all = new ArrayList<>(userIds.size());
        final int chunk = 200;                          // user-service BATCH_GET_MAX
        for (int i = 0; i < userIds.size(); i += chunk) {
            List<Long> sub = userIds.subList(i, Math.min(i + chunk, userIds.size()));
            try {
                all.addAll(userClient.batchGetProfiles(sub));
            } catch (Exception e) {
                log.warn("dh-plan batchGetProfiles failed for chunk size={}", sub.size(), e);
            }
        }
        return all;
    }

    private static int randomInRange(ThreadLocalRandom rnd, int min, int max) {
        if (max < min) return min;
        return rnd.nextInt(min, max + 1);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
