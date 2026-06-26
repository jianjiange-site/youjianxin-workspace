package com.dating.match.exception;

/**
 * match-service 业务错误码(对齐 dating-server/docs/match-service-prd-tech.md)。
 *
 * <p>段划分:
 * <ul>
 *   <li>400~500:cross-cutting</li>
 *   <li>13001~13099:swipe 相关</li>
 *   <li>13101~13199:match 相关</li>
 *   <li>13201~13299:配额 / 订阅 / 金币</li>
 *   <li>13301~13399:feed / 队列</li>
 * </ul>
 */
public final class ErrorCodes {

    private ErrorCodes() {}

    public static final int UNAUTHENTICATED = 401;
    public static final int FORBIDDEN = 403;
    public static final int TOO_MANY_REQUESTS = 429;
    public static final int SYSTEM_ERROR = 500;
    public static final int INVALID_ARGUMENT = 400;

    // ── swipe ──
    public static final int SWIPE_SELF_NOT_ALLOWED = 13001;
    public static final int SWIPE_TARGET_NOT_FOUND = 13002;
    public static final int CONCURRENT_SWIPE = 13003;            // Redisson 锁抢占失败

    // ── match ──
    public static final int ALREADY_MATCHED = 13101;
    public static final int MATCH_NOT_FOUND = 13102;

    // ── quota / subscription / coin ──
    public static final int QUOTA_RIGHT_SWIPE_EXCEEDED = 13201;
    public static final int QUOTA_CARD_EXCEEDED = 13202;
    public static final int QUOTA_SUPER_HI_EXCEEDED = 13203;
    public static final int INSUFFICIENT_COINS = 13204;
    public static final int PAYMENT_RPC_FAILED = 13205;

    // ── feed / 队列 ──
    public static final int FEED_BUILD_FAILED = 13301;
}
