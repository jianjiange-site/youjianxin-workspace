package com.dating.common.alert;

import java.time.Instant;
import java.util.Map;

// 一次告警的不可变快照。Notifier 与 Appender 在调用线程内立刻构造,异步线程消费 — 不依赖 MDC 等线程局部状态。
public record AlertEvent(
        AlertLevel level,
        String scene,
        Throwable throwable,
        String message,
        Map<String, String> context,
        Map<String, String> mdcSnapshot,
        Instant timestamp,
        String env,
        String service,
        String host
) {
}
