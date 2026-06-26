package com.dating.match.constant;

/**
 * 划卡方向(DB user_swipe_history.direction 的取值)。
 *
 * <p>与 proto match.SwipeDirection 对齐:
 * <ul>
 *   <li>1 = LEFT(不喜欢)= proto SWIPE_DIRECTION_LEFT</li>
 *   <li>2 = RIGHT(喜欢)= proto SWIPE_DIRECTION_RIGHT</li>
 *   <li>3 = SUPER_HI(独立 RPC 触发,不由 Swipe RPC 收)</li>
 * </ul>
 */
public final class SwipeDirection {

    private SwipeDirection() {}

    public static final short LEFT = 1;
    public static final short RIGHT = 2;
    public static final short SUPER_HI = 3;
}
