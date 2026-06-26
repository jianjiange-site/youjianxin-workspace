package com.dating.match.recommend;

import com.dating.match.client.UserClient;
import com.dating.match.entity.UserSwipeHistory;
import com.dating.match.mapper.UserSwipeHistoryMapper;
import com.dating.match.recommend.dto.Preference;
import com.dating.youjianxin.proto.user.UserProfile;
import com.dating.youjianxin.proto.user.UserType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 user_swipe_history 聚合 30 天右划画像。
 *
 * <p>步骤:
 * <ol>
 *   <li>取最近 30 天该用户 RIGHT/SUPER_HI swipe(限 200 条防爆)</li>
 *   <li>dhBhRatio 直接从 target_user_type 算,不需 RPC</li>
 *   <li>调 user-service.batchGetProfile 拿 target 资料(age / beauty / race)</li>
 *   <li>聚合均值 / 标准差 / race 分布</li>
 * </ol>
 *
 * <p>详见 docs §4.2.1。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreferenceBuilder {

    private static final int LOOKBACK_DAYS = 30;
    private static final int MAX_SAMPLE = 200;

    /** 样本数低于此值时,Ranker 应该退化到用 user 自身画像作 prior */
    public static final int MIN_SAMPLE_FOR_PERSONALIZE = 10;

    private final UserSwipeHistoryMapper swipeMapper;
    private final UserClient userClient;

    public Preference build(long userId) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(LOOKBACK_DAYS);
        List<UserSwipeHistory> rows = swipeMapper.selectRecentRightSwipes(userId, since, MAX_SAMPLE);
        if (rows.isEmpty()) {
            return empty();
        }

        // dhBhRatio:无 RPC
        int dhCount = 0;
        List<Long> targetIds = new java.util.ArrayList<>(rows.size());
        for (UserSwipeHistory r : rows) {
            targetIds.add(r.getTargetUserId());
            if (r.getTargetUserType() != null && r.getTargetUserType() == 2) {
                // match-service 内部约定:1=BH, 2=DH(见 V1__init_match_tables.sql 与 SwipeService 写入)
                dhCount++;
            }
        }
        double dhBhRatio = (double) dhCount / rows.size();

        // 拉资料聚合
        List<UserProfile> profiles;
        try {
            profiles = userClient.batchGetProfiles(targetIds);
        } catch (Exception e) {
            log.warn("PreferenceBuilder batchGetProfiles failed userId={};dhBhRatio={} 用空 raceDist 兜底",
                    userId, dhBhRatio, e);
            return Preference.builder()
                    .ageMean(0).ageStd(0)
                    .beautyMean(0).beautyStd(0)
                    .raceDist(Map.of())
                    .dhBhRatio(dhBhRatio)
                    .sampleSize(rows.size())
                    .build();
        }

        Map<String, Integer> raceCount = new HashMap<>();
        double ageSum = 0, beautySum = 0;
        double ageSqSum = 0, beautySqSum = 0;
        int n = 0;
        for (UserProfile p : profiles) {
            int age = p.getAge();
            int beauty = p.getBeautyScore();
            String race = p.getRace();
            ageSum += age;
            ageSqSum += (double) age * age;
            beautySum += beauty;
            beautySqSum += (double) beauty * beauty;
            if (race != null && !race.isBlank()) {
                raceCount.merge(race, 1, Integer::sum);
            }
            n++;
        }

        double ageMean = n == 0 ? 0 : ageSum / n;
        double beautyMean = n == 0 ? 0 : beautySum / n;
        double ageStd = stddev(ageSum, ageSqSum, n);
        double beautyStd = stddev(beautySum, beautySqSum, n);

        // race 分布归一化
        Map<String, Double> raceDist = new HashMap<>(raceCount.size());
        int raceTotal = raceCount.values().stream().mapToInt(Integer::intValue).sum();
        if (raceTotal > 0) {
            raceCount.forEach((k, v) -> raceDist.put(k, (double) v / raceTotal));
        }

        return Preference.builder()
                .ageMean(ageMean).ageStd(ageStd)
                .beautyMean(beautyMean).beautyStd(beautyStd)
                .raceDist(raceDist)
                .dhBhRatio(dhBhRatio)
                .sampleSize(rows.size())
                .build();
    }

    private static double stddev(double sum, double sqSum, int n) {
        if (n <= 1) return 0;
        double mean = sum / n;
        double var = (sqSum / n) - mean * mean;
        return var <= 0 ? 0 : Math.sqrt(var);
    }

    private static Preference empty() {
        return Preference.builder()
                .ageMean(0).ageStd(0)
                .beautyMean(0).beautyStd(0)
                .raceDist(Map.of())
                .dhBhRatio(0)
                .sampleSize(0)
                .build();
    }
}
