package com.dating.match.config;

import com.dating.youjianxin.proto.payment.SubscriptionTier;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * 配额表(对齐 dating-server/docs/match-service-prd-tech.md §3.1)。
 *
 * <p>Nacos {@code match-service-<profile>.yaml} 提供:
 * <pre>
 * match:
 *   quota:
 *     FREE:    { right_swipe: 5,  cards: 50,  super_hi: 0 }
 *     WEEKLY:  { right_swipe: 10, cards: 80,  super_hi: 0 }
 *     MONTHLY: { right_swipe: 15, cards: 120, super_hi: 1 }
 *     YEARLY:  { right_swipe: 15, cards: 120, super_hi: 1 }
 * </pre>
 *
 * <p>Nacos 未配置时使用本类内置默认值。
 */
@Configuration
@ConfigurationProperties(prefix = "match.quota")
@Data
public class QuotaConfig {

    private DailyQuota free = new DailyQuota(5, 50, 0);
    private DailyQuota weekly = new DailyQuota(10, 80, 0);
    private DailyQuota monthly = new DailyQuota(15, 120, 1);
    private DailyQuota yearly = new DailyQuota(15, 120, 1);

    public DailyQuota forTier(SubscriptionTier tier) {
        return switch (tier) {
            case SUBSCRIPTION_TIER_WEEKLY -> weekly;
            case SUBSCRIPTION_TIER_MONTHLY -> monthly;
            case SUBSCRIPTION_TIER_YEARLY -> yearly;
            default -> free;
        };
    }

    public Map<SubscriptionTier, DailyQuota> asMap() {
        EnumMap<SubscriptionTier, DailyQuota> m = new EnumMap<>(SubscriptionTier.class);
        m.put(SubscriptionTier.SUBSCRIPTION_TIER_FREE, free);
        m.put(SubscriptionTier.SUBSCRIPTION_TIER_WEEKLY, weekly);
        m.put(SubscriptionTier.SUBSCRIPTION_TIER_MONTHLY, monthly);
        m.put(SubscriptionTier.SUBSCRIPTION_TIER_YEARLY, yearly);
        return m;
    }

    /**
     * 单档订阅的当日配额上限。
     *
     * <p>同一档位的所有字段挂在一起,每天 UTC 00:00 整点随 Redis key
     * {@code match:quota:<user_id>:<yyyymmdd>} 自然滚到下一天(见 PRD §3.1 / §7.3)。
     *
     * <p><b>三个字段不是平行关系,SuperHi 会同时消耗 cards 和 rightSwipe</b>,详见各字段说明。
     */
    @Data
    public static class DailyQuota {
        /**
         * 每日右划次数上限(LEFT 不计,RIGHT 计 1,SuperHi 也计 1,因为本质就是高优先级喜欢)。
         * 到上限后右划返 {@code QUOTA_RIGHT_SWIPE_EXCEEDED}。
         */
        private int rightSwipe;

        /**
         * 每日 feed 整体消费上限(LEFT + RIGHT + SuperHi 全部计 1)。
         * 到上限后 {@code GetTodayFeed} 返 {@code exhausted=true},前端展示"今天已经看完啦"。
         *
         * <p>与 D1 队列容量(240)不同 —— 队列是池子,本字段是消费上限;池子留余量是为了召回多样性。
         */
        private int cards;

        /**
         * 订阅赠送的 SuperHi 当日免费数(MONTHLY/YEARLY=1,其他=0)。
         * 用完之后仍可花金币买 SuperHi,但走 payment-service 扣金币,不再回吃此配额。
         */
        private int superHi;

        public DailyQuota() {}
        public DailyQuota(int rightSwipe, int cards, int superHi) {
            this.rightSwipe = rightSwipe;
            this.cards = cards;
            this.superHi = superHi;
        }
    }
}
