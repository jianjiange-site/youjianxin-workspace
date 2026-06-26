package com.dating.common.alert.throttle;

// 简单同步令牌桶:告警路径 QPS 不高(默认 60/min),synchronized 比 AtomicLong CAS 编码更易读且足够。
// refillIntervalNanos = 60_000_000_000L / ratePerMinute,即每多少 ns 补 1 token。
public final class TokenBucket {

    private final long capacity;
    private final long refillIntervalNanos;
    private long tokens;
    private long lastRefillNanos;

    public TokenBucket(int capacity, int ratePerMinute) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if (ratePerMinute <= 0) throw new IllegalArgumentException("ratePerMinute must be > 0");
        this.capacity = capacity;
        this.refillIntervalNanos = 60_000_000_000L / ratePerMinute;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryTake() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed >= refillIntervalNanos) {
            long refill = elapsed / refillIntervalNanos;
            tokens = Math.min(capacity, tokens + refill);
            lastRefillNanos += refill * refillIntervalNanos;
        }
        if (tokens > 0) {
            tokens--;
            return true;
        }
        return false;
    }

    public synchronized long availableTokens() {
        return tokens;
    }
}
