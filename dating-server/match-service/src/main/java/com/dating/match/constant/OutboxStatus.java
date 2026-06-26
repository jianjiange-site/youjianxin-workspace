package com.dating.match.constant;

/**
 * match_outbox.status 取值。
 *
 * <p>状态机:PENDING → DONE(成功终态) / DEAD(超过最大重试次数,人工介入终态)
 * <br>失败时 attempts++ + next_retry_at exp backoff,保留 PENDING。
 */
public final class OutboxStatus {

    private OutboxStatus() {}

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String DEAD = "DEAD";

    /** 超过该 attempts 数,状态置 DEAD;由 MatchOutboxRetry 内部判定 */
    public static final int MAX_ATTEMPTS = 8;
}
