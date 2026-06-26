package com.dating.match.service;

import com.dating.match.client.PaymentClient;
import com.dating.match.config.QuotaConfig;
import com.dating.match.constant.CacheKeys;
import com.dating.match.exception.BizException;
import com.dating.match.exception.ErrorCodes;
import com.dating.youjianxin.proto.payment.SubscriptionTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 配额服务 —— Redis HASH HINCRBY 加 / 减;无 PG 持久化(AOF 兜底)。
 *
 * <p>详见 docs §3.1 / §6.6。
 * <ul>
 *   <li>quota:&lt;uid&gt;:&lt;yyyymmdd&gt; HASH 36h:fields = right_swipe / cards / super_hi</li>
 *   <li>tier 通过 PaymentClient.getSubscription 查;失败降级 FREE</li>
 *   <li>SuperHi 用完订阅赠送后允许买金币(SwipeService 调用本服务时不递增 super_hi 计数)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String FIELD_RIGHT_SWIPE = "right_swipe";
    private static final String FIELD_CARDS = "cards";
    private static final String FIELD_SUPER_HI = "super_hi";

    private final StringRedisTemplate redis;
    private final PaymentClient paymentClient;
    private final QuotaConfig quotaConfig;

    public SubscriptionTier currentTier(long userId) {
        return paymentClient.getSubscription(userId);
    }

    public QuotaSnapshot snapshot(long userId) {
        SubscriptionTier tier = currentTier(userId);
        QuotaConfig.DailyQuota dailyQuota = quotaConfig.forTier(tier);
        Used used = readUsed(userId);
        return new QuotaSnapshot(tier, dailyQuota, used);
    }

    /** 检查 + 扣 1 卡;到上限抛 QUOTA_CARD_EXCEEDED。 */
    public void incrCardOrThrow(long userId, QuotaConfig.DailyQuota quota) {
        Long after = hincrBy(userId, FIELD_CARDS, 1);
        if (after > quota.getCards()) {
            hincrBy(userId, FIELD_CARDS, -1);  // 回滚
            throw new BizException(ErrorCodes.QUOTA_CARD_EXCEEDED, "daily cards exhausted");
        }
        ensureExpire(userId);
    }

    /** 检查 + 扣 1 右划;到上限抛 QUOTA_RIGHT_SWIPE_EXCEEDED。 */
    public void incrRightSwipeOrThrow(long userId, QuotaConfig.DailyQuota quota) {
        Long after = hincrBy(userId, FIELD_RIGHT_SWIPE, 1);
        if (after > quota.getRightSwipe()) {
            hincrBy(userId, FIELD_RIGHT_SWIPE, -1);  // 回滚
            throw new BizException(ErrorCodes.QUOTA_RIGHT_SWIPE_EXCEEDED, "daily right_swipe exhausted");
        }
        ensureExpire(userId);
    }

    /** 尝试用订阅赠送的 SuperHi 配额。成功返回 true(已扣 +1),false 表示已用完(调用方走金币购买)。 */
    public boolean tryUseSuperHiQuota(long userId, QuotaConfig.DailyQuota quota) {
        if (quota.getSuperHi() <= 0) return false;
        Long after = hincrBy(userId, FIELD_SUPER_HI, 1);
        if (after != null && after > quota.getSuperHi()) {
            hincrBy(userId, FIELD_SUPER_HI, -1);   // 回滚,用完了
            return false;
        }
        ensureExpire(userId);
        return true;
    }

    public Used readUsed(long userId) {
        return new Used(readField(userId, FIELD_RIGHT_SWIPE),
                        readField(userId, FIELD_CARDS),
                        readField(userId, FIELD_SUPER_HI));
    }

    // ────────────────────────── helpers ──────────────────────────

    private Long hincrBy(long userId, String field, long delta) {
        return redis.opsForHash().increment(quotaKey(userId), field, delta);
    }

    private int readField(long userId, String field) {
        Object v = redis.opsForHash().get(quotaKey(userId), field);
        if (v == null) return 0;
        try { return Integer.parseInt(v.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private void ensureExpire(long userId) {
        redis.expire(quotaKey(userId), CacheKeys.QUOTA_TTL);
    }

    private String quotaKey(long userId) {
        return CacheKeys.quota(userId, LocalDate.now().format(YYYYMMDD));
    }

    public record Used(int rightSwipe, int cards, int superHi) {}
    public record QuotaSnapshot(SubscriptionTier tier, QuotaConfig.DailyQuota dailyQuota, Used used) {}
}
