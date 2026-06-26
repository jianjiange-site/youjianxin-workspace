package com.dating.match.recommend;

import com.dating.match.mapper.UserSwipeHistoryMapper;
import com.dating.match.recommend.dto.Preference;
import com.dating.youjianxin.proto.user.Candidate;
import com.dating.youjianxin.proto.user.UserType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * D1 打分器。对单个候选池(BH 或 DH)按 4 项 base + 2 个加性 bonus 算 score,
 * 排序后取 top N。
 *
 * <p>详见 docs §4.2.3 + §4.2.4。
 *
 * <pre>
 *   S(c) = 0.45 * preference_similarity(c, pref)
 *        + 0.30 * normalize(c.beauty_score)
 *        + 0.15 * distance_decay(c)          # DH 固定 0.5
 *        + 0.10 * activity_score(c)          # DH 固定 0.5
 *        + mutual_like_bonus(c)              # BH only, +0.20 if c 曾右划过 caller
 *        + new_bh_bonus(c)                   # BH only, +0.20 if 注册 ≤3 天
 * </pre>
 *
 * <p>不做 MMR / 不做 ε-greedy(docs §4.2.4 已删)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Ranker {

    @Value("${match.score.bh-weights.pref:0.45}") private double bhWPref;
    @Value("${match.score.bh-weights.beauty:0.30}") private double bhWBeauty;
    @Value("${match.score.bh-weights.distance:0.15}") private double bhWDistance;
    @Value("${match.score.bh-weights.activity:0.10}") private double bhWActivity;

    @Value("${match.score.dh-weights.pref:0.45}") private double dhWPref;
    @Value("${match.score.dh-weights.beauty:0.30}") private double dhWBeauty;
    @Value("${match.score.dh-weights.distance:0.15}") private double dhWDistance;
    @Value("${match.score.dh-weights.activity:0.10}") private double dhWActivity;

    @Value("${match.score.mutual-like-bonus:0.20}") private double mutualLikeBonus;
    @Value("${match.score.new-bh-bonus:0.20}") private double newBhBonus;
    @Value("${match.score.new-bh-window-days:3}") private int newBhWindowDays;

    private final UserSwipeHistoryMapper swipeMapper;

    /** 对 BH 候选池打分;mutual_like_bonus 需要批量反查 swipe history。 */
    public List<Candidate> rankBhPool(long callerUserId, List<Candidate> pool, Preference pref, long nowMs) {
        if (pool.isEmpty()) return List.of();
        Set<Long> mutualLikeSet = lookupMutualLikers(callerUserId, pool);
        long newWindowMs = newBhWindowDays * 86_400_000L;
        List<Scored> scored = new ArrayList<>(pool.size());
        for (Candidate c : pool) {
            double s = bhWPref * prefSim(c, pref)
                     + bhWBeauty * normalizeBeauty(c)
                     + bhWDistance * distanceDecay(c)
                     + bhWActivity * activityScore(c, nowMs);
            if (mutualLikeSet.contains(c.getUserId())) s += mutualLikeBonus;
            if (isNewBh(c, nowMs, newWindowMs)) s += newBhBonus;
            scored.add(new Scored(c, s));
        }
        return takeTopN(scored, pool.size());
    }

    /** 对 DH 候选池打分;mutual_like / new_bh bonus 永远 0(DH 不享受)。 */
    public List<Candidate> rankDhPool(List<Candidate> pool, Preference pref) {
        if (pool.isEmpty()) return List.of();
        List<Scored> scored = new ArrayList<>(pool.size());
        for (Candidate c : pool) {
            double s = dhWPref * prefSim(c, pref)
                     + dhWBeauty * normalizeBeauty(c)
                     + dhWDistance * 0.5   // DH 无距离,固定 0.5
                     + dhWActivity * 0.5;  // DH 无活跃,固定 0.5
            scored.add(new Scored(c, s));
        }
        return takeTopN(scored, pool.size());
    }

    // ────────────────────────── score 子项 ──────────────────────────

    /** 偏好相似度:age + beauty + race 三项乘积;样本不足时退化为 0.5 中性值 */
    private double prefSim(Candidate c, Preference pref) {
        if (pref.getSampleSize() < PreferenceBuilder.MIN_SAMPLE_FOR_PERSONALIZE) {
            return 0.5;
        }
        double ageStd = pref.getAgeStd() < 1 ? 5 : pref.getAgeStd();
        double beautyStd = pref.getBeautyStd() < 1 ? 10 : pref.getBeautyStd();
        double ageZ = (c.getAge() - pref.getAgeMean()) / ageStd;
        double beautyZ = (c.getBeautyScore() - pref.getBeautyMean()) / beautyStd;
        double ageGauss = Math.exp(-0.5 * ageZ * ageZ);
        double beautyGauss = Math.exp(-0.5 * beautyZ * beautyZ);
        double raceP = (c.getRace() == null || c.getRace().isBlank())
                ? 0.3                                         // race 未填的兜底权重
                : pref.getRaceDist().getOrDefault(c.getRace(), 0.1);
        return ageGauss * beautyGauss * raceP;
    }

    private static double normalizeBeauty(Candidate c) {
        return Math.max(0, Math.min(100, c.getBeautyScore())) / 100.0;
    }

    /** 距离衰减:exp(-d/50km);Phase 1 同城 distance 多为 0 → 1.0;DH = -1 → 0.5 中性 */
    private static double distanceDecay(Candidate c) {
        double d = c.getDistanceKm();
        if (d < 0) return 0.5;
        return Math.exp(-d / 50.0);
    }

    /** BH 活跃度:exp(-(now - last_open) 天数 / 7);DH last_open 没意义 → 0.5 */
    private static double activityScore(Candidate c, long nowMs) {
        if (c.getUserType() == UserType.USER_TYPE_DH) return 0.5;
        long lastOpen = c.getLastOpenAtMs();
        if (lastOpen <= 0) return 0.0;
        double days = (nowMs - lastOpen) / 86_400_000.0;
        return Math.exp(-days / 7.0);
    }

    private static boolean isNewBh(Candidate c, long nowMs, long windowMs) {
        return c.getUserType() == UserType.USER_TYPE_BH
                && c.getCreatedAtMs() > 0
                && (nowMs - c.getCreatedAtMs()) <= windowMs;
    }

    /** 批量查 candidate 哪些曾右划过 caller(mutual_like_bonus 信号) */
    private Set<Long> lookupMutualLikers(long callerUserId, List<Candidate> pool) {
        List<Long> ids = pool.stream().map(Candidate::getUserId).toList();
        if (ids.isEmpty()) return Set.of();
        try {
            return new HashSet<>(swipeMapper.selectCandidatesWhoRightSwipedCaller(callerUserId, ids));
        } catch (Exception e) {
            log.warn("lookupMutualLikers failed caller={};fallback empty(mutual_like_bonus 全 0)", callerUserId, e);
            return Set.of();
        }
    }

    private static List<Candidate> takeTopN(List<Scored> scored, int limit) {
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Candidate> out = new ArrayList<>(Math.min(limit, scored.size()));
        for (int i = 0; i < scored.size() && i < limit; i++) out.add(scored.get(i).candidate);
        return out;
    }

    private record Scored(Candidate candidate, double score) {}
}
