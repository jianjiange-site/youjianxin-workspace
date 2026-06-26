package com.dating.match.service;

import com.dating.match.client.UserClient;
import com.dating.match.constant.CacheKeys;
import com.dating.match.manager.SwipeHistoryManager;
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
import java.util.Comparator;
import java.util.List;

/**
 * D0 实时召回 + 按 bh_ratio merge,RPUSH 到 match:feed:&lt;uid&gt; LIST。
 *
 * <p>详见 docs §4.1。
 * <ul>
 *   <li>DH 池(目标 240):L0~L3 渐进扩范围(本 MVP 实现仅 L0,L1~L3 待补)</li>
 *   <li>BH 池(目标 240):严格单层 L0(同城 / age±5 / beauty±15 / 同人种 / 7d 活跃)</li>
 *   <li>字典序排序(4 级 BH:new_bh → same_race → age_diff → beauty;3 级 DH:same_race → age_diff → beauty)</li>
 *   <li>按 cold_start.bh_ratio merge,DH 补齐 BH 缺口</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColdStartService {

    @Value("${match.cold-start.bh-ratio:0.20}")
    private double bhRatio;

    @Value("${match.cold-start.bh.age-window:5}")
    private int ageWindow;

    @Value("${match.cold-start.bh.beauty-window:15}")
    private int beautyWindow;

    @Value("${match.cold-start.bh.active-days:7}")
    private int activeDays;

    @Value("${match.score.new-bh-window-days:3}")
    private int newBhWindowDays;

    @Value("${match.feed.target-size:240}")
    private int targetSize;

    private final UserClient userClient;
    private final SwipeHistoryManager swipeHistoryManager;
    private final StringRedisTemplate redis;

    /**
     * 为 userId 实时构造一个 240 张的 feed 并 RPUSH 到 match:feed:&lt;uid&gt;。
     * 不预先 DEL(允许重复调用追加;LPOP 即消费保证不重复)。
     */
    public int buildAndPush(long userId) {
        UserProfile self = lookupSelf(userId);
        if (self == null) {
            log.warn("ColdStart buildAndPush: caller self profile not found userId={}", userId);
            return 0;
        }
        Gender targetGender = (self.getGender() == Gender.GENDER_MALE) ? Gender.GENDER_FEMALE : Gender.GENDER_MALE;
        List<Long> exclude = swipeHistoryManager.allTargetIds(userId);

        List<Candidate> dhPool = userClient.listDhCandidates(
                targetGender,
                Math.max(18, self.getAge() - ageWindow),
                Math.min(99, self.getAge() + ageWindow),
                Math.max(0, beautyScore(self) - beautyWindow),
                Math.min(100, beautyScore(self) + beautyWindow),
                self.getRace().isEmpty() ? null : List.of(self.getRace()),
                exclude,
                targetSize);

        List<Candidate> bhPool = userClient.nearbyUsers(
                userId,
                Math.max(18, self.getAge() - ageWindow),
                Math.min(99, self.getAge() + ageWindow),
                Math.max(0, beautyScore(self) - beautyWindow),
                Math.min(100, beautyScore(self) + beautyWindow),
                self.getRace().isEmpty() ? null : List.of(self.getRace()),
                activeDays,
                exclude,
                targetSize);

        // 字典序排序(BH 4 级,DH 3 级)
        bhPool = new ArrayList<>(bhPool);
        bhPool.sort(bhComparator(self, System.currentTimeMillis()));
        dhPool = new ArrayList<>(dhPool);
        dhPool.sort(dhComparator(self));

        // 按 bh_ratio merge,DH 补齐 BH 缺口
        int targetBh = (int) Math.round(targetSize * bhRatio);
        int actualBh = Math.min(targetBh, bhPool.size());
        int actualDh = Math.min(targetSize - actualBh, dhPool.size());
        List<Candidate> merged = interleave(bhPool.subList(0, actualBh), dhPool.subList(0, actualDh));

        // RPUSH 到 Redis LIST
        String key = CacheKeys.feedList(userId);
        if (merged.isEmpty()) return 0;
        String[] members = new String[merged.size()];
        for (int i = 0; i < merged.size(); i++) {
            Candidate c = merged.get(i);
            short targetUserTypeDb = c.getUserType() == UserType.USER_TYPE_DH ? (short) 2 : (short) 1;
            members[i] = CacheKeys.feedListMember(c.getUserId(), targetUserTypeDb);
        }
        redis.opsForList().rightPushAll(key, members);
        redis.expire(key, CacheKeys.FEED_LIST_TTL);
        log.info("ColdStart buildAndPush userId={} pushed={} (bh={}, dh={}, bh_pool_recalled={}, dh_pool_recalled={})",
                userId, merged.size(), actualBh, actualDh, bhPool.size(), dhPool.size());
        return merged.size();
    }

    private UserProfile lookupSelf(long userId) {
        try {
            List<UserProfile> profiles = userClient.batchGetProfiles(List.of(userId));
            return profiles.isEmpty() ? null : profiles.get(0);
        } catch (Exception e) {
            log.error("lookupSelf failed userId={}", userId, e);
            return null;
        }
    }

    private static int beautyScore(UserProfile p) {
        return Math.max(0, Math.min(100, p.getBeautyScore()));
    }

    /** BH 池排序键:新 BH → 同人种 → 年龄差 → 颜值 */
    private Comparator<Candidate> bhComparator(UserProfile self, long nowMs) {
        long newWindowMs = newBhWindowDays * 86_400_000L;
        return Comparator
                .comparing((Candidate c) -> isNewBh(c, nowMs, newWindowMs)).reversed()
                .thenComparing((Candidate c) -> sameRaceFlag(c, self)).reversed()
                .thenComparingInt(c -> Math.abs(c.getAge() - self.getAge()))
                .thenComparingInt(Candidate::getBeautyScore).reversed();
    }

    /** DH 池排序键:同人种 → 年龄差 → 颜值 */
    private Comparator<Candidate> dhComparator(UserProfile self) {
        return Comparator
                .comparing((Candidate c) -> sameRaceFlag(c, self)).reversed()
                .thenComparingInt(c -> Math.abs(c.getAge() - self.getAge()))
                .thenComparingInt(Candidate::getBeautyScore).reversed();
    }

    private static boolean isNewBh(Candidate c, long nowMs, long windowMs) {
        return c.getUserType() == UserType.USER_TYPE_BH
                && c.getCreatedAtMs() > 0 && (nowMs - c.getCreatedAtMs()) <= windowMs;
    }

    private static int sameRaceFlag(Candidate c, UserProfile self) {
        if (self.getRace() == null || self.getRace().isEmpty()) return 0;
        return self.getRace().equalsIgnoreCase(c.getRace()) ? 1 : 0;
    }

    /** 每 round(1/bhRatio) 张 DH 间塞 1 张 BH,BH 用完后纯 DH 排到底 */
    private static List<Candidate> interleave(List<Candidate> bh, List<Candidate> dh) {
        List<Candidate> out = new ArrayList<>(bh.size() + dh.size());
        if (bh.isEmpty()) { out.addAll(dh); return out; }
        int step = Math.max(1, (int) Math.round((double) dh.size() / Math.max(1, bh.size())));
        int bi = 0, di = 0;
        while (bi < bh.size() || di < dh.size()) {
            // 先放 step 张 DH
            for (int k = 0; k < step && di < dh.size(); k++) out.add(dh.get(di++));
            // 再放 1 张 BH
            if (bi < bh.size()) out.add(bh.get(bi++));
        }
        return out;
    }
}
