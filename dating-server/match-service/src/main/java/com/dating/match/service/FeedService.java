package com.dating.match.service;

import com.dating.match.client.UserClient;
import com.dating.match.constant.CacheKeys;
import com.dating.match.service.dto.CardItem;
import com.dating.youjianxin.proto.user.DhCityOverride;
import com.dating.youjianxin.proto.user.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GetTodayFeed:LPOP + 二次过滤 + 空时自动重建。
 *
 * <p>详见 docs §4.3。
 *
 * <p>每次拉 5 张:
 * <ol>
 *   <li>校验配额(cards_used &lt; card_limit)</li>
 *   <li>LPOP need;不足则触发 ColdStartService.buildAndPush 重建</li>
 *   <li>对 LPOP 出的卡片 SMISMEMBER match:swiped:&lt;uid&gt; 二次过滤,命中即丢</li>
 *   <li>batchGetProfile 拼装 Card VO</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private static final int MAX_REBUILD_ATTEMPTS = 2;

    private final StringRedisTemplate redis;
    private final QuotaService quotaService;
    private final ColdStartService coldStartService;
    private final UserClient userClient;

    public FeedResult getTodayFeed(long userId, int count) {
        if (count <= 0) count = 5;
        QuotaService.QuotaSnapshot snap = quotaService.snapshot(userId);
        int remainingCards = Math.max(0, snap.dailyQuota().getCards() - snap.used().cards());
        if (remainingCards == 0) {
            return new FeedResult(Collections.emptyList(), true);
        }
        int need = Math.min(count, remainingCards);

        List<CardItem> cards = new ArrayList<>(need);
        int rebuildAttempts = 0;
        while (cards.size() < need) {
            int want = need - cards.size();
            List<String> popped = redis.opsForList()
                    .leftPop(CacheKeys.feedList(userId), want);
            if (popped == null || popped.isEmpty()) {
                if (rebuildAttempts >= MAX_REBUILD_ATTEMPTS) break;
                int pushed = coldStartService.buildAndPush(userId);
                rebuildAttempts++;
                if (pushed == 0) break;
                continue;
            }
            // 二次过滤:SMISMEMBER match:swiped:<uid>(per-element,简单可靠)
            List<Long> targetIds = popped.stream().map(FeedService::parseTargetId).toList();
            for (int i = 0; i < popped.size(); i++) {
                long tid = targetIds.get(i);
                Boolean hit = redis.opsForSet().isMember(CacheKeys.swipedSet(userId), String.valueOf(tid));
                if (Boolean.TRUE.equals(hit)) {
                    continue;   // 已 swipe 过,丢弃
                }
                short ut = parseTargetType(popped.get(i));
                cards.add(new CardItem(tid, ut));
            }
        }

        // batchGetProfile 拼装
        if (cards.isEmpty()) {
            return new FeedResult(Collections.emptyList(), false);
        }
        List<Long> ids = cards.stream().map(CardItem::targetUserId).toList();
        Map<Long, UserProfile> profileMap = new HashMap<>(ids.size());
        try {
            List<UserProfile> profiles = userClient.batchGetProfiles(ids);
            for (UserProfile p : profiles) profileMap.put(p.getUserId(), p);
        } catch (Exception e) {
            log.warn("batchGetProfile failed for feed userId={} count={};downgrade to bare ids", userId, ids.size(), e);
        }

        // DH 卡片做"同 state 不同 city"位置覆盖(防穿帮 + 本地化展示)。
        // 失败 fail-open:UserClient 内部异常时返空 map,DH 不下发位置,前端按"无位置"渲染。
        List<Long> dhIds = cards.stream()
                .filter(c -> c.targetUserTypeDb() == 2)
                .map(CardItem::targetUserId)
                .toList();
        Map<Long, DhCityOverride> dhOverrides = dhIds.isEmpty()
                ? Collections.emptyMap()
                : userClient.pickDhCitiesForCaller(userId, dhIds);

        List<CardItem> enriched = new ArrayList<>(cards.size());
        for (CardItem c : cards) {
            UserProfile p = profileMap.get(c.targetUserId());
            CardItem withProfile = c.withProfile(p);
            DhCityOverride ov = dhOverrides.get(c.targetUserId());
            if (ov != null) {
                withProfile = withProfile.withDhCityOverride(ov.getStateCode(), ov.getCity());
            }
            enriched.add(withProfile);
        }
        return new FeedResult(enriched, false);
    }

    private static long parseTargetId(String member) {
        int idx = member.indexOf(':');
        return idx < 0 ? Long.parseLong(member) : Long.parseLong(member.substring(0, idx));
    }

    private static short parseTargetType(String member) {
        int idx = member.indexOf(':');
        if (idx < 0) return 1;
        try { return Short.parseShort(member.substring(idx + 1)); }
        catch (NumberFormatException e) { return 1; }
    }

    public record FeedResult(List<CardItem> cards, boolean exhausted) {}
}
