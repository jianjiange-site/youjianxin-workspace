package com.dating.im.constant;

/**
 * Presence(在线状态)相关的 Redis key。遵循仓库约定 {@code <service>:<domain>:<id>}。
 */
public final class PresenceKeys {

    private PresenceKeys() {}

    /**
     * 在线用户集合(ZSet):member = userId(字符串),score = 上线时刻(epoch 毫秒)。
     * {@code ZCARD} = 当前在线人数;{@code ZSCORE != null} = 该用户是否在线。
     */
    public static final String ONLINE_ZSET = "im:presence:online";
}
