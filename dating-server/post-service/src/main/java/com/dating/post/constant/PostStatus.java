package com.dating.post.constant;

/** {@code posts.status} 枚举值。 */
public final class PostStatus {

    /** 已删除(逻辑或物理)。 */
    public static final int DELETED = 0;

    /** 正常可见。 */
    public static final int NORMAL = 1;

    /** 审核中(本期不实际使用,留扩展点)。 */
    public static final int REVIEWING = 2;

    private PostStatus() {
    }
}
