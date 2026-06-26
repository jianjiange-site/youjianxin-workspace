package com.dating.match.constant;

/**
 * like_record.source 取值。
 *
 * <p>与 proto match.LikeSource 对齐(ProtoMapper 在 grpc 层做枚举字符串↔proto 转换)。
 * BH/DH 来源在数据库层用此 source 列差分:
 * <ul>
 *   <li>{@link #SWIPE_RIGHT} / {@link #SUPER_HI}:真人 BH 行为</li>
 *   <li>{@link #DH_PLAN_ONLINE} / {@link #DH_PLAN_OFFLINE}:DH 模拟计划生成
 *       (详见 docs §6.3 / §6.4 「单 BH 24h 内 DH like 上限」走 source IN 过滤)</li>
 * </ul>
 */
public final class LikeSource {

    private LikeSource() {}

    /** 单向右划;可能后续因互划升级成 match,like_record 行不删 */
    public static final String SWIPE_RIGHT = "SWIPE_RIGHT";

    /** SuperHi;立即创建 match,但 like_record 仍归档 */
    public static final String SUPER_HI = "SUPER_HI";

    /** DH 模拟「在线计划」生成的 LIKE;OnlinePlanGenerator(docs §6.3.1)写入 */
    public static final String DH_PLAN_ONLINE = "DH_PLAN_ONLINE";

    /** DH 模拟「离线计划」生成的 LIKE;OfflinePlanGenerator(docs §6.3.2)写入 */
    public static final String DH_PLAN_OFFLINE = "DH_PLAN_OFFLINE";

    /** DH 来源集合 — 用于 24h 上限 / 列表过滤的 SQL `source IN (...)` */
    public static final String[] DH_SOURCES = { DH_PLAN_ONLINE, DH_PLAN_OFFLINE };
}
