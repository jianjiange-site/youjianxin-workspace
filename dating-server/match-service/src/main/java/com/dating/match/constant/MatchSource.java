package com.dating.match.constant;

/**
 * match.source 取值(入口动作维度,与 BH/DH 正交)。
 *
 * <p>与 proto match.MatchSource 对齐;BH/DH 由 user_id 反查 user-service.user_type 得到,不冗余。
 * 后续若有推荐位 / 活动 / 系统配对等新入口,在此追加。
 */
public final class MatchSource {

    private MatchSource() {}

    /** 划卡页常规匹配(BH 互划立即 / DH 延迟 15s-2min 回调) */
    public static final String SWIPE_MATCH = "SWIPE_MATCH";

    /** 划卡页 SuperHi(订阅赠送或金币购买,无视对方意愿) */
    public static final String SWIPE_SUPER_HI = "SWIPE_SUPER_HI";
}
