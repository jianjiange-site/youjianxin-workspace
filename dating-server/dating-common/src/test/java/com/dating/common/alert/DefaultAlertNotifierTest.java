package com.dating.common.alert;

import com.dating.common.alert.send.AlertSender;
import com.dating.common.alert.throttle.AlertThrottler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAlertNotifierTest {

    private AlertThrottler throttler;
    private AlertSender sender;
    private HostInfo host;
    private DefaultAlertNotifier notifier;

    @BeforeEach
    void setUp() {
        throttler = mock(AlertThrottler.class);
        sender = mock(AlertSender.class);
        host = new HostInfo("test", "svc", "h");
        notifier = new DefaultAlertNotifier(throttler, sender, host, true);
    }

    @Test
    void criticalGoesThroughGlobalOnlyAndEnqueues() {
        when(throttler.tryAcquireGlobalOnly(any())).thenReturn(AlertThrottler.Decision.ACCEPT);
        notifier.critical("scene", new RuntimeException("x"), Map.of("k", "v"));
        verify(throttler, times(1)).tryAcquireGlobalOnly(any());
        verify(throttler, never()).tryAcquire(any());
        verify(sender, times(1)).enqueue(any());
    }

    @Test
    void errorGoesThroughFullThrottle() {
        when(throttler.tryAcquire(any())).thenReturn(AlertThrottler.Decision.ACCEPT);
        notifier.error("scene", new RuntimeException("x"), Map.of());
        verify(throttler, times(1)).tryAcquire(any());
        verify(throttler, never()).tryAcquireGlobalOnly(any());
        verify(sender, times(1)).enqueue(any());
    }

    @Test
    void droppedDecisionDoesNotEnqueue() {
        when(throttler.tryAcquire(any())).thenReturn(AlertThrottler.Decision.DROP_SIGNATURE);
        notifier.error("scene", new RuntimeException(), Map.of());
        verify(sender, never()).enqueue(any());
    }

    @Test
    void disabledNotifierIsNoOp() {
        DefaultAlertNotifier off = new DefaultAlertNotifier(throttler, sender, host, false);
        off.critical("s", new RuntimeException(), Map.of());
        off.error("s", new RuntimeException(), Map.of());
        off.warn("s", "msg", Map.of());
        verify(throttler, never()).tryAcquire(any());
        verify(throttler, never()).tryAcquireGlobalOnly(any());
        verify(sender, never()).enqueue(any());
    }

    @Test
    void warnIsSentAsWarnLevel() {
        when(throttler.tryAcquire(any())).thenReturn(AlertThrottler.Decision.ACCEPT);
        notifier.warn("scene", "low disk", Map.of());
        verify(sender, times(1)).enqueue(any());
    }
}
