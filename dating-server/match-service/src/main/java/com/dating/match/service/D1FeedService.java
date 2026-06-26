package com.dating.match.service;

import com.dating.match.client.UserClient;
import com.dating.match.constant.CacheKeys;
import com.dating.match.manager.SwipeHistoryManager;
import com.dating.match.recommend.PreferenceBuilder;
import com.dating.match.recommend.Ranker;
import com.dating.match.recommend.dto.Preference;
import com.dating.youjianxin.proto.user.Candidate;
import com.dating.youjianxin.proto.user.Gender;
import com.dating.youjianxin.proto.user.UserProfile;
import com.dating.youjianxin.proto.user.UserType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * D1 离线 feed 生成:个性化召回 → 打分 → merge → DEL+RPUSH。
 *
 * <p>详见 docs §4.2:
 * <ul>
 *   <li>偏好建模:30 天 RIGHT 聚合(PreferenceBuilder)</li>
 *   <li>召回:DH 池 + BH 池各 240(基于 preference 的 age/beauty/race 范围)</li>
 *   <li>打分:0.45 pref + 0.30 beauty + 0.15 distance + 0.10 activity + mutual_like + new_bh</li>
 *   <li>merge:bh_ratio = L1(运营固定) + L2(个性化偏移,基于 dhBhRatio)</li>
 * </ul>
 *
 * <p>样本数 &lt; 10 时,Preference 退化为 0.5 中性值(Ranker 内部处理),实际效果接近 D0。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class D1FeedService {

    @Value("${match.d1.bh-ratio:0.40}")            private double bhRatioBase;
    @Value("${match.d1.preference-enabled:true}")  private boolean preferenceEnabled;
    @Value("${match.d1.preference-offset:0.20}")   private double preferenceOffsetMax;

    @Value("${match.feed.target-size:240}")        private int targetSize;
    @Value("${match.cold-start.bh.age-window:5}")  private int ageWindow;
    @Value("${match.cold-start.bh.beauty-window:15}") private int beautyWindow;
    @Value("${match.cold-start.bh.active-days:7}") private int activeDays;

    private final PreferenceBuilder preferenceBuilder;
    private final Ranker ranker;
    private final UserClient userClient;
    private final SwipeHistoryManager swipeHistoryManager;
    private final StringRedisTemplate redis;

    /**
     * 为单个 userId 生成 D1 feed 并 DEL+RPUSH 到 match:feed:&lt;uid&gt;。
     *
     * @return 实际 push 的卡片数;0 表示召回都空 / 用户已注销
     */
    public int generateForUser(long userId) {
        UserProfile self = lookupSelf(userId);
        if (self == null) {
            log.warn("D1 skip: caller profile not found userId={}", userId);
            return 0;
        }
        Preference pref = preferenceBuilder.build(userId);
        Gender targetGender = (self.getGender() == Gender.GENDER_MALE) ? Gender.GENDER_FEMALE : Gender.GENDER_MALE;
        List<Long> exclude = swipeHistoryManager.allTargetIds(userId);

        // 召回范围:有偏好用 pref;无偏好回退用户自身画像作 prior
        int ageMin, ageMax, beautyMin, beautyMax;
        List<String> races;
        if (pref.getSampleSize() >= PreferenceBuilder.MIN_SAMPLE_FOR_PERSONALIZE) {
            ageMin = Math.max(18, (int) Math.round(pref.getAgeMean() - 2 * Math.max(1, pref.getAgeStd())));
            ageMax = Math.min(99, (int) Math.round(pref.getAgeMean() + 2 * Math.max(1, pref.getAgeStd())));
            beautyMin = Math.max(0, (int) Math.round(pref.getBeautyMean() - 2 * Math.max(1, pref.getBeautyStd())));
            beautyMax = Math.min(100, (int) Math.round(pref.getBeautyMean() + 2 * Math.max(1, pref.getBeautyStd())));
            races = pref.getRaceDist().isEmpty() ? null : new ArrayList<>(pref.getRaceDist().keySet());
        } else {
            ageMin = Math.max(18, self.getAge() - ageWindow);
            ageMax = Math.min(99, self.getAge() + ageWindow);
            int sb = clampBeauty(self.getBeautyScore());
            beautyMin = Math.max(0, sb - beautyWindow);
            beautyMax = Math.min(100, sb + beautyWindow);
            races = (self.getRace() == null || self.getRace().isEmpty()) ? null : List.of(self.getRace());
        }

        List<Candidate> dhPool = userClient.listDhCandidates(
                targetGender, ageMin, ageMax, beautyMin, beautyMax, races, exclude, targetSize);
        List<Candidate> bhPool = userClient.nearbyUsers(
                userId, ageMin, ageMax, beautyMin, beautyMax, races, activeDays, exclude, targetSize);

        long nowMs = System.currentTimeMillis();
        List<Candidate> rankedDh = ranker.rankDhPool(dhPool, pref);
        List<Candidate> rankedBh = ranker.rankBhPool(userId, bhPool, pref, nowMs);

        // bh_ratio L1 + L2
        double finalBhRatio = computeFinalBhRatio(pref);
        int targetBh = (int) Math.round(targetSize * finalBhRatio);
        int actualBh = Math.min(targetBh, rankedBh.size());
        int actualDh = Math.min(targetSize - actualBh, rankedDh.size());
        List<Candidate> merged = interleave(rankedBh.subList(0, actualBh), rankedDh.subList(0, actualDh));

        // DEL + RPUSH 覆盖式重写
        String key = CacheKeys.feedList(userId);
        if (merged.isEmpty()) {
            log.warn("D1 generated empty feed userId={} pref.sampleSize={} dhPool={} bhPool={}",
                    userId, pref.getSampleSize(), dhPool.size(), bhPool.size());
            return 0;
        }
        String[] members = new String[merged.size()];
        for (int i = 0; i < merged.size(); i++) {
            Candidate c = merged.get(i);
            short ut = c.getUserType() == UserType.USER_TYPE_DH ? (short) 2 : (short) 1;
            members[i] = CacheKeys.feedListMember(c.getUserId(), ut);
        }
        redis.delete(key);
        redis.opsForList().rightPushAll(key, members);
        redis.expire(key, CacheKeys.FEED_LIST_TTL);
        log.info("D1 generated userId={} pushed={} bh={} dh={} (finalBhRatio={}, pref.sampleSize={}, dhBhRatio={})",
                userId, merged.size(), actualBh, actualDh, finalBhRatio, pref.getSampleSize(), pref.getDhBhRatio());
        return merged.size();
    }

    /** L1 + L2:运营基础比例 + 个性化偏移(dhBhRatio 越大,bh 比例越低) */
    private double computeFinalBhRatio(Preference pref) {
        double ratio = bhRatioBase;
        if (preferenceEnabled && pref.getSampleSize() >= PreferenceBuilder.MIN_SAMPLE_FOR_PERSONALIZE) {
            double offset = (0.5 - pref.getDhBhRatio()) * preferenceOffsetMax * 2;
            offset = Math.max(-preferenceOffsetMax, Math.min(preferenceOffsetMax, offset));
            ratio += offset;
        }
        return Math.max(0, Math.min(1, ratio));
    }

    private UserProfile lookupSelf(long userId) {
        try {
            List<UserProfile> profiles = userClient.batchGetProfiles(List.of(userId));
            return profiles.isEmpty() ? null : profiles.get(0);
        } catch (Exception e) {
            log.error("D1 lookupSelf failed userId={}", userId, e);
            return null;
        }
    }

    private static int clampBeauty(int b) { return Math.max(0, Math.min(100, b)); }

    /** 每 round(1/bhRatio) 张 DH 间塞 1 张 BH,BH 用完后纯 DH 排到底 */
    private static List<Candidate> interleave(List<Candidate> bh, List<Candidate> dh) {
        List<Candidate> out = new ArrayList<>(bh.size() + dh.size());
        if (bh.isEmpty()) { out.addAll(dh); return out; }
        int step = Math.max(1, (int) Math.round((double) dh.size() / Math.max(1, bh.size())));
        int bi = 0, di = 0;
        while (bi < bh.size() || di < dh.size()) {
            for (int k = 0; k < step && di < dh.size(); k++) out.add(dh.get(di++));
            if (bi < bh.size()) out.add(bh.get(bi++));
        }
        return out;
    }
}
