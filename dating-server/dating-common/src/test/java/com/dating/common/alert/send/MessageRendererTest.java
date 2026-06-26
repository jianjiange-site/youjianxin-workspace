package com.dating.common.alert.send;

import com.dating.common.alert.AlertEvent;
import com.dating.common.alert.AlertLevel;
import com.dating.common.alert.SummaryEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRendererTest {

    private final MessageRenderer renderer = new MessageRenderer();

    @Test
    void rendersCriticalWithSceneExceptionContextAndStack() {
        AlertEvent e = build(AlertLevel.CRITICAL, "payment.callback",
                new IllegalStateException("sign mismatch"),
                Map.of("orderId", "100", "channel", "alipay"));
        String md = renderer.render(e);
        assertThat(md).contains("[CRITICAL] test-svc @ prod");
        assertThat(md).contains("**场景**: payment.callback");
        assertThat(md).contains("IllegalStateException");
        assertThat(md).contains("sign mismatch");
        assertThat(md).contains("orderId: 100");
        assertThat(md).contains("traceId");
        assertThat(md).contains("**堆栈**:");
    }

    @Test
    void rendersErrorWithLoggerNameAndShorterStack() {
        AlertEvent e = build(AlertLevel.ERROR, "com.dating.user.UserService",
                new RuntimeException("db down"), Map.of());
        String md = renderer.render(e);
        assertThat(md).contains("[ERROR] test-svc @ prod");
        assertThat(md).contains("com.dating.user.UserService");
        assertThat(md).contains("RuntimeException");
    }

    @Test
    void rendersSummary() {
        SummaryEvent s = new SummaryEvent(
                0xabcL, "java.lang.RuntimeException", "com.x.Y#z:99",
                3, 47,
                Instant.parse("2026-05-28T01:00:00Z"),
                Instant.parse("2026-05-28T01:09:55Z"),
                Duration.ofMinutes(10),
                "prod", "test-svc", "host-01"
        );
        String md = renderer.render(s);
        assertThat(md).contains("[SUMMARY] test-svc @ prod");
        assertThat(md).contains("共出现 **50** 次");
        assertThat(md).contains("已发送 3 条");
        assertThat(md).contains("截流 **47** 条");
        assertThat(md).contains("abc"); // hex sig
        assertThat(md).contains("com.x.Y#z:99");
    }

    @Test
    void truncatesLongMessageOnUtf8Boundary() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 100; i++) s.append("中"); // 3 bytes each = 300 bytes
        String truncated = MessageRenderer.truncateUtf8(s.toString(), 50);
        // 不应在多字节中间断字,长度应该 <= 50 字节(加上 …)
        byte[] bytes = truncated.substring(0, truncated.length() - 1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(bytes.length).isLessThanOrEqualTo(50);
        assertThat(truncated).endsWith("…");
    }

    @Test
    void emptyMdcShowsDash() {
        AlertEvent e = new AlertEvent(AlertLevel.ERROR, "scene",
                new RuntimeException("x"), "",
                Map.of(), Map.of(), Instant.now(),
                "prod", "test-svc", "host-01");
        String md = renderer.render(e);
        assertThat(md).contains("**traceId**: `-`");
    }

    @Test
    void mdcTraceIdAndUserIdAreRendered() {
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put("traceId", "abc123");
        mdc.put("userId", "1024");
        AlertEvent e = new AlertEvent(AlertLevel.ERROR, "scene",
                new RuntimeException("x"), "",
                Map.of(), mdc, Instant.now(), "prod", "test-svc", "host");
        String md = renderer.render(e);
        assertThat(md).contains("`abc123`").contains("`1024`");
    }

    private static AlertEvent build(AlertLevel level, String scene, Throwable t, Map<String, String> ctx) {
        return new AlertEvent(level, scene, t, "",
                ctx, Map.of("traceId", "trace-1", "userId", "u-1"),
                Instant.parse("2026-05-28T01:00:00Z"),
                "prod", "test-svc", "host-01");
    }
}
