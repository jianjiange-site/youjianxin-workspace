package com.dating.common.alert.throttle;

import com.dating.common.alert.AlertEvent;
import com.dating.common.alert.AlertProperties;
import com.dating.common.alert.SummaryEvent;
import com.dating.common.alert.send.AlertSender;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// 全局签名注册表 + 定时 sweeper。
// 双轨 rollover:tryAccept 内惰性触发 + sweeper 主动扫,确保过期摘要不会丢。
public class ThrottleRegistry {

    private static final Logger log = LoggerFactory.getLogger(ThrottleRegistry.class);

    private final ConcurrentHashMap<Long, SignatureWindow> windows = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final AlertSender sender;
    private final long windowNanos;
    private final int maxPerWindow;
    private final long cleanupIntervalNanos;
    // 窗口 lastSeen 超过窗口长度的 2 倍后从 map 中移除,避免内存累积
    private final long retentionNanos;

    private ScheduledFuture<?> sweeperHandle;

    public ThrottleRegistry(AlertProperties.Throttle cfg, ScheduledExecutorService scheduler, AlertSender sender) {
        this.scheduler = scheduler;
        this.sender = sender;
        this.windowNanos = cfg.getWindowDuration().toNanos();
        this.maxPerWindow = cfg.getMaxPerWindow();
        this.cleanupIntervalNanos = cfg.getCleanupInterval().toNanos();
        this.retentionNanos = windowNanos * 2;
    }

    public SignatureWindow.Outcome recordAndCheck(long sig, AlertEvent e) {
        SignatureWindow w = windows.computeIfAbsent(sig,
                k -> new SignatureWindow(sig, e, windowNanos, maxPerWindow));
        SignatureWindow.AcceptResult r = w.tryAccept(e);
        SummaryEvent rollover = r.rolloverSummary();
        if (rollover != null) {
            sender.enqueueSummary(rollover);
        }
        return r.outcome();
    }

    @PostConstruct
    public void start() {
        sweeperHandle = scheduler.scheduleAtFixedRate(
                this::sweep,
                cleanupIntervalNanos,
                cleanupIntervalNanos,
                TimeUnit.NANOSECONDS
        );
    }

    @PreDestroy
    public void stop() {
        if (sweeperHandle != null) sweeperHandle.cancel(false);
    }

    private void sweep() {
        try {
            windows.forEach((sig, w) -> {
                SummaryEvent s = w.expireSnapshot();
                if (s != null) sender.enqueueSummary(s);
                if (w.canRemove(retentionNanos)) windows.remove(sig, w);
            });
        } catch (Throwable t) {
            // sweeper 异常不能中断后续调度,只 debug;不可调 log.error(避免循环触发)
            log.debug("alert throttle sweeper failed: {}", t.toString());
        }
    }

    public int activeWindowCount() {
        return windows.size();
    }
}
