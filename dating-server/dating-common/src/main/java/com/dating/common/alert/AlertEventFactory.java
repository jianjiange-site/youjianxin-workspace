package com.dating.common.alert;

import java.time.Instant;
import java.util.Map;

// Notifier 与 Appender 都用这个构造 AlertEvent;两者只是 caller 不同,字段语义一致。
public final class AlertEventFactory {

    private AlertEventFactory() {}

    public static AlertEvent build(
            AlertLevel level,
            String scene,
            Throwable throwable,
            String message,
            Map<String, String> context,
            Map<String, String> mdcSnapshot,
            HostInfo host
    ) {
        return new AlertEvent(
                level,
                scene == null ? "unknown" : scene,
                throwable,
                message == null ? "" : message,
                context == null || context.isEmpty() ? Map.of() : Map.copyOf(context),
                mdcSnapshot == null || mdcSnapshot.isEmpty() ? Map.of() : Map.copyOf(mdcSnapshot),
                Instant.now(),
                host.env(),
                host.service(),
                host.host()
        );
    }
}
