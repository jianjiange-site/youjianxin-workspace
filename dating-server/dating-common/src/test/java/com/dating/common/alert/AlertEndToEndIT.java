package com.dating.common.alert;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AlertTestApplication.class, properties = {
        "dating.alert.enabled=true",
        "dating.alert.wework.corp-id=corp-x",
        "dating.alert.wework.corp-secret=secret-y",
        "dating.alert.wework.agent-id=1000001",
        "dating.alert.wework.to-user=ZhangSan",
        "dating.alert.wework.token-refresh-skew=60s",
        "dating.alert.async.shutdown-timeout=2s",
        "dating.alert.throttle.window-duration=200ms",
        "dating.alert.throttle.max-per-window=2",
        "dating.alert.throttle.cleanup-interval=80ms",
        "dating.alert.throttle.global-per-minute=6000",
        "dating.alert.throttle.global-burst=100",
        "spring.application.name=alert-it"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlertEndToEndIT {

    private static final HttpServer server;
    private static final AtomicInteger getTokenCount = new AtomicInteger();
    private static final List<String> receivedBodies = Collections.synchronizedList(new ArrayList<>());
    private static volatile String firstSendBody;
    private static final AtomicInteger sendCount = new AtomicInteger();

    static {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/cgi-bin/gettoken", exchange -> {
                int n = getTokenCount.incrementAndGet();
                byte[] body = String.format("{\"errcode\":0,\"access_token\":\"TK-%d\",\"expires_in\":7200}", n)
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/cgi-bin/message/send", exchange -> {
                int n = sendCount.incrementAndGet();
                byte[] req = exchange.getRequestBody().readAllBytes();
                receivedBodies.add(new String(req, StandardCharsets.UTF_8));
                byte[] body;
                if (n == 1 && firstSendBody != null) {
                    body = firstSendBody.getBytes(StandardCharsets.UTF_8);
                } else {
                    body = "{\"errcode\":0,\"errmsg\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                }
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void overrideBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("dating.alert.wework.base-url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll
    void stopStub() {
        server.stop(0);
    }

    @BeforeEach
    void resetState() {
        receivedBodies.clear();
        sendCount.set(0);
        firstSendBody = null;
    }

    @Autowired
    private AlertNotifier alertNotifier;

    @Test
    void notifierErrorReachesStubAsMarkdownPayload() throws Exception {
        alertNotifier.error("payment.charge", new IllegalStateException("upstream 500"),
                Map.of("orderId", "100"));
        await(() -> !receivedBodies.isEmpty());
        String body = receivedBodies.get(0);
        assertThat(body).contains("payment.charge");
        assertThat(body).contains("IllegalStateException");
        assertThat(body).contains("\"msgtype\":\"markdown\"");
        assertThat(body).contains("\"agentid\":1000001");
        assertThat(body).contains("\"touser\":\"ZhangSan\"");
    }

    @Test
    void logErrorTriggersAppenderEndToEnd() throws Exception {
        Logger l = LoggerFactory.getLogger("com.dating.test.PaymentService");
        l.error("payment failed", new RuntimeException("from-appender"));
        await(() -> !receivedBodies.isEmpty());
        String body = receivedBodies.get(0);
        assertThat(body).contains("PaymentService");
        assertThat(body).contains("from-appender");
    }

    @Test
    void sameSignatureIsThrottledThenSummaryEmitted() throws Exception {
        for (int i = 0; i < 10; i++) {
            alertNotifier.error("repeat", makeSameException(), Map.of());
        }
        await(() -> receivedBodies.stream().anyMatch(s -> s.contains("[SUMMARY]")));
        long errorCount = receivedBodies.stream().filter(s -> s.contains("[ERROR]")).count();
        long summaryCount = receivedBodies.stream().filter(s -> s.contains("[SUMMARY]")).count();
        // maxPerWindow=2 → 最多 2 条 ERROR;至少 1 条 SUMMARY
        assertThat(errorCount).isLessThanOrEqualTo(2);
        assertThat(summaryCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void expiredTokenTriggersForceRefreshAndRetry() throws Exception {
        firstSendBody = "{\"errcode\":42001,\"errmsg\":\"access_token expired\"}";
        int gettokenBefore = getTokenCount.get();
        alertNotifier.critical("token-flow", new RuntimeException("x"), Map.of());
        await(() -> sendCount.get() >= 2);
        assertThat(sendCount.get()).isEqualTo(2);
        // 至少触发一次 forceRefresh(client 缓存命中时初次 get 不调,但 errcode=42001 之后必然刷一次)
        assertThat(getTokenCount.get()).isGreaterThan(gettokenBefore);
    }

    private static RuntimeException makeSameException() {
        RuntimeException ex = new RuntimeException("same");
        ex.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.dating.test.Same", "method", "Same.java", 42)
        });
        return ex;
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("condition not met within 3s, receivedBodies=" + receivedBodies);
    }
}
