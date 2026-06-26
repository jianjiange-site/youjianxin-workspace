package com.dating.common.alert.throttle;

import com.dating.common.alert.AlertEvent;
import com.dating.common.alert.AlertLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionSignatureTest {

    @Test
    void sameClassSameFrameProducesSameHash() {
        long a = ExceptionSignature.of(eventWith(throwAt("methodA", 10)));
        long b = ExceptionSignature.of(eventWith(throwAt("methodA", 10)));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentLineProducesDifferentHash() {
        long a = ExceptionSignature.of(eventWith(throwAt("m", 10)));
        long b = ExceptionSignature.of(eventWith(throwAt("m", 11)));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentExceptionClassProducesDifferentHash() {
        Throwable t1 = new IllegalStateException("x");
        Throwable t2 = new RuntimeException("x");
        long a = ExceptionSignature.of(eventWith(t1));
        long b = ExceptionSignature.of(eventWith(t2));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void nullThrowableUsesSceneAndLevel() {
        AlertEvent e1 = new AlertEvent(AlertLevel.WARN, "sceneX", null, "msg", Map.of(), Map.of(),
                Instant.now(), "test", "svc", "host");
        AlertEvent e2 = new AlertEvent(AlertLevel.WARN, "sceneX", null, "msg", Map.of(), Map.of(),
                Instant.now(), "test", "svc", "host");
        AlertEvent e3 = new AlertEvent(AlertLevel.WARN, "sceneY", null, "msg", Map.of(), Map.of(),
                Instant.now(), "test", "svc", "host");
        assertThat(ExceptionSignature.of(e1)).isEqualTo(ExceptionSignature.of(e2));
        assertThat(ExceptionSignature.of(e1)).isNotEqualTo(ExceptionSignature.of(e3));
    }

    @Test
    void topFrameOfNullReturnsNoFrame() {
        assertThat(ExceptionSignature.topFrameOf(null)).isEqualTo("noframe");
    }

    private static AlertEvent eventWith(Throwable t) {
        return new AlertEvent(AlertLevel.ERROR, "scene", t, "", Map.of(), Map.of(),
                Instant.now(), "test", "svc", "host");
    }

    private static Throwable throwAt(String method, int line) {
        Throwable t = new RuntimeException("boom");
        StackTraceElement[] st = new StackTraceElement[] {
                new StackTraceElement("com.example.Cls", method, "Cls.java", line),
                new StackTraceElement("com.example.Other", "delegate", "Other.java", 100)
        };
        t.setStackTrace(st);
        return t;
    }
}
