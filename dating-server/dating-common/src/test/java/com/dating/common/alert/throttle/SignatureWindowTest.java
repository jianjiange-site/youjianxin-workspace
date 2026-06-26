package com.dating.common.alert.throttle;

import com.dating.common.alert.AlertEvent;
import com.dating.common.alert.AlertLevel;
import com.dating.common.alert.SummaryEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureWindowTest {

    @Test
    void acceptsUpToMaxThenDrops() {
        SignatureWindow w = new SignatureWindow(0x42L, evt(), seconds(10), 3);
        assertThat(w.tryAccept(evt()).outcome()).isEqualTo(SignatureWindow.Outcome.ACCEPT);
        assertThat(w.tryAccept(evt()).outcome()).isEqualTo(SignatureWindow.Outcome.ACCEPT);
        assertThat(w.tryAccept(evt()).outcome()).isEqualTo(SignatureWindow.Outcome.ACCEPT);
        assertThat(w.tryAccept(evt()).outcome()).isEqualTo(SignatureWindow.Outcome.DROP);
        assertThat(w.tryAccept(evt()).outcome()).isEqualTo(SignatureWindow.Outcome.DROP);
    }

    @Test
    void rolloverEmitsSummaryWhenDroppedNonZero() throws InterruptedException {
        SignatureWindow w = new SignatureWindow(0x42L, evt(), 50_000_000L /* 50ms */, 1);
        assertThat(w.tryAccept(evt()).outcome()).isEqualTo(SignatureWindow.Outcome.ACCEPT);
        assertThat(w.tryAccept(evt()).outcome()).isEqualTo(SignatureWindow.Outcome.DROP);
        assertThat(w.tryAccept(evt()).outcome()).isEqualTo(SignatureWindow.Outcome.DROP);
        Thread.sleep(80);
        SignatureWindow.AcceptResult r = w.tryAccept(evt());
        // 窗口过期 → rollover 内带 summary,dropped 应为 2
        assertThat(r.rolloverSummary()).isNotNull();
        SummaryEvent s = r.rolloverSummary();
        assertThat(s.accepted()).isEqualTo(1);
        assertThat(s.dropped()).isEqualTo(2);
        // 新窗口此条 accept
        assertThat(r.outcome()).isEqualTo(SignatureWindow.Outcome.ACCEPT);
    }

    @Test
    void expireSnapshotReturnsSummaryOnceThenNullsOut() throws InterruptedException {
        SignatureWindow w = new SignatureWindow(1L, evt(), 50_000_000L, 1);
        w.tryAccept(evt());
        w.tryAccept(evt()); // dropped++
        Thread.sleep(80);
        SummaryEvent first = w.expireSnapshot();
        assertThat(first).isNotNull();
        assertThat(first.dropped()).isEqualTo(1);
        // 第二次扫不应再 emit(dropped 已清零)
        assertThat(w.expireSnapshot()).isNull();
    }

    @Test
    void canRemoveWhenIdleLongerThanRetention() throws InterruptedException {
        SignatureWindow w = new SignatureWindow(1L, evt(), seconds(10), 3);
        w.tryAccept(evt());
        Thread.sleep(20);
        assertThat(w.canRemove(10_000_000L /* 10ms */)).isTrue();
        assertThat(w.canRemove(seconds(10))).isFalse();
    }

    private static long seconds(int s) {
        return s * 1_000_000_000L;
    }

    private static AlertEvent evt() {
        return new AlertEvent(AlertLevel.ERROR, "scene", new RuntimeException("x"),
                "", Map.of(), Map.of(), Instant.now(), "test", "svc", "host");
    }
}
