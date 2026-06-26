package com.dating.common.alert.throttle;

import com.dating.common.alert.AlertEvent;
import com.dating.common.alert.AlertLevel;
import com.dating.common.alert.AlertProperties;
import com.dating.common.alert.SummaryEvent;
import com.dating.common.alert.send.AlertSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ThrottleRegistryTest {

    private ScheduledExecutorService scheduler;
    private AlertSender sender;
    private ThrottleRegistry registry;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        sender = mock(AlertSender.class);
        AlertProperties.Throttle cfg = new AlertProperties.Throttle();
        cfg.setWindowDuration(Duration.ofMillis(80));
        cfg.setMaxPerWindow(2);
        cfg.setCleanupInterval(Duration.ofMillis(40));
        registry = new ThrottleRegistry(cfg, scheduler, sender);
        registry.start();
    }

    @AfterEach
    void tearDown() {
        registry.stop();
        scheduler.shutdownNow();
    }

    @Test
    void underLimitAccepts() {
        AlertEvent e = evt();
        long sig = ExceptionSignature.of(e);
        assertThat(registry.recordAndCheck(sig, e)).isEqualTo(SignatureWindow.Outcome.ACCEPT);
        assertThat(registry.recordAndCheck(sig, e)).isEqualTo(SignatureWindow.Outcome.ACCEPT);
        assertThat(registry.recordAndCheck(sig, e)).isEqualTo(SignatureWindow.Outcome.DROP);
        verify(sender, never()).enqueueSummary(any(SummaryEvent.class));
    }

    @Test
    void sweeperEmitsSummaryAfterWindowExpiresWithDropped() throws InterruptedException {
        AlertEvent e = evt();
        long sig = ExceptionSignature.of(e);
        registry.recordAndCheck(sig, e);
        registry.recordAndCheck(sig, e);
        registry.recordAndCheck(sig, e); // 1 dropped
        // 等窗口过期 + 至少一轮 sweep
        Thread.sleep(200);
        ArgumentCaptor<SummaryEvent> cap = ArgumentCaptor.forClass(SummaryEvent.class);
        verify(sender, atLeast(1)).enqueueSummary(cap.capture());
        SummaryEvent s = cap.getValue();
        assertThat(s.dropped()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void noSummaryWhenNothingDropped() throws InterruptedException {
        AlertEvent e = evt();
        long sig = ExceptionSignature.of(e);
        registry.recordAndCheck(sig, e); // 1 accepted, 0 dropped
        Thread.sleep(200);
        verify(sender, never()).enqueueSummary(any(SummaryEvent.class));
    }

    private static AlertEvent evt() {
        Throwable t = new RuntimeException("x");
        t.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.example.Cls", "m", "Cls.java", 1)
        });
        return new AlertEvent(AlertLevel.ERROR, "scene", t, "", Map.of(), Map.of(),
                Instant.now(), "test", "svc", "host");
    }
}
