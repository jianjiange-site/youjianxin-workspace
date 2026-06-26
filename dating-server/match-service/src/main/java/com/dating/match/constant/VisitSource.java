package com.dating.match.constant;

/**
 * visit_record.source 取值。
 *
 * <p>V4 起 visit_record 加 source 列差分访问来源,默认 {@link #PROFILE_VIEW};
 * DH 模拟来源对应 OnlinePlanGenerator / OfflinePlanGenerator(详见 docs §6.3 / §6.4)。
 */
public final class VisitSource {

    private VisitSource() {}

    /** 真人点开他人主页;mobile-gateway → RecordVisit gRPC 落库 */
    public static final String PROFILE_VIEW = "PROFILE_VIEW";

    /** DH 模拟「在线计划」生成的 VISIT;OnlinePlanGenerator(docs §6.3.1)写入 */
    public static final String DH_PLAN_ONLINE = "DH_PLAN_ONLINE";

    /** DH 模拟「离线计划」生成的 VISIT;OfflinePlanGenerator(docs §6.3.2)写入 */
    public static final String DH_PLAN_OFFLINE = "DH_PLAN_OFFLINE";

    /** DH 来源集合 — 用于 24h 上限 / 列表过滤的 SQL `source IN (...)` */
    public static final String[] DH_SOURCES = { DH_PLAN_ONLINE, DH_PLAN_OFFLINE };
}
