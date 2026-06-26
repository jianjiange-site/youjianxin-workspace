package com.dating.match.constant;

/**
 * dh_interaction_task.scene 取值(对应 PG SMALLINT 列)。
 *
 * <p>详见 docs §6.3:
 * <ul>
 *   <li>{@link #ONLINE}:OnlinePlanGenerator(每 1 min)产出,
 *       「用户正在 App 内,涓涓细流的关注感」</li>
 *   <li>{@link #OFFLINE}:OfflinePlanGenerator(每 20 min)产出,
 *       「离线 20 min+ 给集中的 DH 互动,下次开 App 看到一批新鲜事」</li>
 * </ul>
 */
public final class DhTaskScene {

    private DhTaskScene() {}

    public static final short ONLINE = 1;
    public static final short OFFLINE = 2;

    /** Redis last_scene 值(供 OfflinePlanGenerator 跳过本次离线期已生成的用户) */
    public static final String LAST_SCENE_ONLINE = "ONLINE";
    public static final String LAST_SCENE_OFFLINE = "OFFLINE";
}
