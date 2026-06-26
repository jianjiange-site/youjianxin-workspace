package com.dating.common.alert.throttle;

import com.dating.common.alert.AlertEvent;
import com.dating.common.alert.SummaryEvent;

import java.time.Duration;
import java.time.Instant;

// 单异常签名的滑动窗口。synchronized 保护 rollover/accept 原子性,告警路径 QPS 不高,锁竞争可接受。
// 两种 rollover 触发:
//   1) tryAccept 看到窗口过期 → 内部 snapshot 旧窗口(若 dropped>0)再重置
//   2) ThrottleRegistry sweeper 定时扫到过期 → 调 expireSnapshot 得到 snapshot,Registry 转交 sender
public class SignatureWindow {

    public enum Outcome { ACCEPT, DROP }

    public record AcceptResult(Outcome outcome, SummaryEvent rolloverSummary) {}

    private final long signature;
    private final long windowNanos;
    private final int maxPerWindow;

    private long windowStartNanos;
    private long firstSeenWallMillis;
    private long lastSeenWallMillis;
    private long lastSeenNanos;
    private int accepted;
    private int dropped;
    private AlertEvent firstSample;

    public SignatureWindow(long signature, AlertEvent firstEvent, long windowNanos, int maxPerWindow) {
        this.signature = signature;
        this.windowNanos = windowNanos;
        this.maxPerWindow = maxPerWindow;
        long now = System.nanoTime();
        long wall = System.currentTimeMillis();
        this.windowStartNanos = now;
        this.firstSeenWallMillis = wall;
        this.lastSeenWallMillis = wall;
        this.lastSeenNanos = now;
        this.firstSample = firstEvent;
    }

    public synchronized AcceptResult tryAccept(AlertEvent e) {
        long now = System.nanoTime();
        long wall = System.currentTimeMillis();
        SummaryEvent rollover = null;
        if (now - windowStartNanos > windowNanos) {
            if (dropped > 0) rollover = snapshotSummary();
            resetWindow(now, wall, e);
        }
        lastSeenNanos = now;
        lastSeenWallMillis = wall;
        if (accepted < maxPerWindow) {
            accepted++;
            return new AcceptResult(Outcome.ACCEPT, rollover);
        } else {
            dropped++;
            return new AcceptResult(Outcome.DROP, rollover);
        }
    }

    // sweeper 看到窗口过期且有 dropped 时调用一次,生成摘要并标记 dropped=0 防重复
    public synchronized SummaryEvent expireSnapshot() {
        long now = System.nanoTime();
        if (now - windowStartNanos > windowNanos && dropped > 0) {
            SummaryEvent s = snapshotSummary();
            dropped = 0;
            return s;
        }
        return null;
    }

    // 用于 sweeper 判定是否可以从 map 中移除(长期无活动)
    public synchronized boolean canRemove(long retentionNanos) {
        long now = System.nanoTime();
        return now - lastSeenNanos > retentionNanos;
    }

    private void resetWindow(long nowNanos, long nowMillis, AlertEvent newFirst) {
        this.windowStartNanos = nowNanos;
        this.firstSeenWallMillis = nowMillis;
        this.lastSeenWallMillis = nowMillis;
        this.accepted = 0;
        this.dropped = 0;
        this.firstSample = newFirst;
    }

    private SummaryEvent snapshotSummary() {
        AlertEvent sample = firstSample;
        String exceptionClass = sample.throwable() != null
                ? sample.throwable().getClass().getName()
                : "(no exception)";
        String topFrame = ExceptionSignature.topFrameOf(sample.throwable());
        return new SummaryEvent(
                signature,
                exceptionClass,
                topFrame,
                accepted,
                dropped,
                Instant.ofEpochMilli(firstSeenWallMillis),
                Instant.ofEpochMilli(lastSeenWallMillis),
                Duration.ofNanos(windowNanos),
                sample.env(),
                sample.service(),
                sample.host()
        );
    }
}
