package com.dating.match.constant;

/**
 * dh_interaction_task.action 取值(对应 PG SMALLINT 列)。
 *
 * <p>详见 docs §6.3 / V4 migration。
 */
public final class DhTaskAction {

    private DhTaskAction() {}

    /** 落 like_record:DH 给 BH like 一次 */
    public static final short LIKE = 1;

    /** 落 visit_record:DH 访问 BH 主页一次(visit_count UPSERT 累加) */
    public static final short VISIT = 2;
}
