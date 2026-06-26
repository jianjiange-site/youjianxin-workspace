package com.dating.common.alert;

import com.dating.common.alert.send.AlertSender;
import com.dating.common.alert.throttle.AlertThrottler;
import org.slf4j.MDC;

import java.util.Map;

public class DefaultAlertNotifier implements AlertNotifier {

    private final AlertThrottler throttler;
    private final AlertSender sender;
    private final HostInfo host;
    private final boolean enabled;

    public DefaultAlertNotifier(AlertThrottler throttler, AlertSender sender, HostInfo host, boolean enabled) {
        this.throttler = throttler;
        this.sender = sender;
        this.host = host;
        this.enabled = enabled;
    }

    @Override
    public void critical(String scene, Throwable ex, Map<String, String> context) {
        if (!enabled) return;
        AlertEvent event = AlertEventFactory.build(
                AlertLevel.CRITICAL, scene, ex, null, context, mdcSnapshot(), host);
        if (throttler.tryAcquireGlobalOnly(event) == AlertThrottler.Decision.ACCEPT) {
            sender.enqueue(event);
        }
    }

    @Override
    public void error(String scene, Throwable ex, Map<String, String> context) {
        if (!enabled) return;
        AlertEvent event = AlertEventFactory.build(
                AlertLevel.ERROR, scene, ex, null, context, mdcSnapshot(), host);
        if (throttler.tryAcquire(event) == AlertThrottler.Decision.ACCEPT) {
            sender.enqueue(event);
        }
    }

    @Override
    public void warn(String scene, String message, Map<String, String> context) {
        if (!enabled) return;
        AlertEvent event = AlertEventFactory.build(
                AlertLevel.WARN, scene, null, message, context, mdcSnapshot(), host);
        if (throttler.tryAcquire(event) == AlertThrottler.Decision.ACCEPT) {
            sender.enqueue(event);
        }
    }

    private static Map<String, String> mdcSnapshot() {
        Map<String, String> m = MDC.getCopyOfContextMap();
        return m == null ? Map.of() : m;
    }
}
