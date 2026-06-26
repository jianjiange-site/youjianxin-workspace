package com.dating.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dating.payment.entity.UserSubscription;
import com.dating.payment.mapper.UserSubscriptionMapper;
import com.google.protobuf.Timestamp;
import com.dating.youjianxin.proto.payment.BaseResponse;
import com.dating.youjianxin.proto.payment.GetSubscriptionRequest;
import com.dating.youjianxin.proto.payment.GetSubscriptionResponse;
import com.dating.youjianxin.proto.payment.SubscriptionInfo;
import com.dating.youjianxin.proto.payment.SubscriptionTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 订阅服务 —— 查询用户当前订阅档位 + 到期时间。
 *
 * <p>策略:
 * <ul>
 *   <li>无 user_subscription 行(未订阅) → FREE,is_active=false</li>
 *   <li>有行但 expires_at &lt; now() → FREE,is_active=false(订阅已过期)</li>
 *   <li>有行且 expires_at &ge; now() → tier 原值,is_active=true</li>
 * </ul>
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /** DB tier 取值与 proto SubscriptionTier 对齐(1=FREE 2=WEEKLY 3=MONTHLY 4=YEARLY) */
    private static final short TIER_FREE = 1;
    private static final short TIER_WEEKLY = 2;
    private static final short TIER_MONTHLY = 3;
    private static final short TIER_YEARLY = 4;

    private final UserSubscriptionMapper userSubscriptionMapper;

    public SubscriptionService(UserSubscriptionMapper userSubscriptionMapper) {
        this.userSubscriptionMapper = userSubscriptionMapper;
    }

    /**
     * 激活 / 续期订阅。
     *
     * <ul>
     *   <li>无记录 → INSERT tier=newTier, expires_at=now+duration</li>
     *   <li>未过期(expires_at > now) → tier 只升不降, expires_at 从当前到期日顺延</li>
     *   <li>已过期(expires_at ≤ now) → tier=newTier, expires_at 从 now 开始</li>
     * </ul>
     */
    @Transactional
    public void activateSubscription(long userId, short newTier, long durationDays, String source) {
        if (newTier <= TIER_FREE) {
            log.warn("[SUB] activateSubscription ignored: userId={} newTier={} not a paid tier", userId, newTier);
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime newExpiresAt = now.plus(Duration.ofDays(durationDays));

        UserSubscription sub = userSubscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscription>().eq(UserSubscription::getUserId, userId));

        if (sub == null) {
            UserSubscription insert = new UserSubscription();
            insert.setUserId(userId);
            insert.setTier(newTier);
            insert.setExpiresAt(newExpiresAt);
            insert.setSource(source);
            insert.setCreatedAt(now);
            insert.setUpdatedAt(now);
            userSubscriptionMapper.insert(insert);
            log.info("[SUB] activateSubscription insert: userId={} tier={} expiresAt={} source={}",
                    userId, newTier, newExpiresAt, source);
            return;
        }

        OffsetDateTime currentExpiresAt = sub.getExpiresAt();
        boolean isActive = currentExpiresAt != null && currentExpiresAt.isAfter(now);

        short effectiveTier;
        OffsetDateTime effectiveExpiresAt;
        if (isActive) {
            effectiveTier = sub.getTier() != null && sub.getTier() > newTier ? sub.getTier() : newTier;
            effectiveExpiresAt = currentExpiresAt.plus(Duration.ofDays(durationDays));
        } else {
            effectiveTier = newTier;
            effectiveExpiresAt = newExpiresAt;
        }

        UserSubscription update = new UserSubscription();
        update.setUserId(userId);
        update.setTier(effectiveTier);
        update.setExpiresAt(effectiveExpiresAt);
        update.setUpdatedAt(now);
        userSubscriptionMapper.update(update,
                new LambdaUpdateWrapper<UserSubscription>().eq(UserSubscription::getUserId, userId));

        log.info("[SUB] activateSubscription update: userId={} oldTier={} newTier={} effectiveTier={}"
                        + " oldExpires={} newExpires={} source={} isActive={}",
                userId, sub.getTier(), newTier, effectiveTier,
                currentExpiresAt, effectiveExpiresAt, source, isActive);
    }

    public GetSubscriptionResponse getSubscription(GetSubscriptionRequest request) {
        long userId = request.getUserId();
        if (userId <= 0) {
            return GetSubscriptionResponse.newBuilder()
                    .setBase(BaseResponse.newBuilder().setCode(400).setMessage("user_id required"))
                    .build();
        }

        UserSubscription sub = userSubscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscription>().eq(UserSubscription::getUserId, userId));

        SubscriptionInfo.Builder info = SubscriptionInfo.newBuilder();
        if (sub == null) {
            info.setTier(toProtoTier(TIER_FREE)).setIsActive(false);
        } else {
            OffsetDateTime expiresAt = sub.getExpiresAt();
            boolean active = expiresAt != null && expiresAt.toInstant().isAfter(Instant.now())
                    && sub.getTier() != null && sub.getTier() > TIER_FREE;
            if (active) {
                info.setTier(toProtoTier(sub.getTier()))
                        .setIsActive(true)
                        .setExpiresAt(toProtoTimestamp(expiresAt));
            } else {
                // 过期 → 降级 FREE
                info.setTier(toProtoTier(TIER_FREE)).setIsActive(false);
                if (expiresAt != null) {
                    info.setExpiresAt(toProtoTimestamp(expiresAt));
                }
            }
        }

        return GetSubscriptionResponse.newBuilder()
                .setBase(BaseResponse.newBuilder().setCode(0).setMessage("OK"))
                .setSubscription(info)
                .build();
    }

    private static SubscriptionTier toProtoTier(short db) {
        return switch (db) {
            case 1 -> SubscriptionTier.SUBSCRIPTION_TIER_FREE;
            case 2 -> SubscriptionTier.SUBSCRIPTION_TIER_WEEKLY;
            case 3 -> SubscriptionTier.SUBSCRIPTION_TIER_MONTHLY;
            case 4 -> SubscriptionTier.SUBSCRIPTION_TIER_YEARLY;
            default -> SubscriptionTier.SUBSCRIPTION_TIER_UNSPECIFIED;
        };
    }

    private static Timestamp toProtoTimestamp(OffsetDateTime odt) {
        Instant instant = odt.toInstant();
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
