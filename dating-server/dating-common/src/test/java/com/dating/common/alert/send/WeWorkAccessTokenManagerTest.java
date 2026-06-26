package com.dating.common.alert.send;

import com.dating.common.alert.AlertProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeWorkAccessTokenManagerTest {

    private HttpServer server;
    private final AtomicInteger getTokenCalls = new AtomicInteger();
    private volatile String tokenBodyTemplate = "{\"errcode\":0,\"access_token\":\"TOKEN-%d\",\"expires_in\":7200}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> {
            int n = getTokenCalls.incrementAndGet();
            byte[] body = String.format(tokenBodyTemplate, n).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private WeWorkAccessTokenManager newManager() {
        AlertProperties.WeWork cfg = new AlertProperties.WeWork();
        cfg.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        cfg.setCorpId("corp-x");
        cfg.setCorpSecret("secret-y");
        cfg.setTokenRefreshSkew(Duration.ofSeconds(60));
        cfg.setReadTimeoutMs(5000);
        return new WeWorkAccessTokenManager(cfg, HttpClient.newHttpClient());
    }

    @Test
    void secondGetWithinTtlIsCached() throws Exception {
        WeWorkAccessTokenManager m = newManager();
        String t1 = m.get();
        String t2 = m.get();
        assertThat(t1).isEqualTo(t2);
        assertThat(getTokenCalls.get()).isEqualTo(1);
    }

    @Test
    void forceRefreshBumpsToken() throws Exception {
        WeWorkAccessTokenManager m = newManager();
        String t1 = m.get();
        String t2 = m.forceRefresh();
        assertThat(t2).isNotEqualTo(t1);
        assertThat(getTokenCalls.get()).isEqualTo(2);
    }

    @Test
    void errcodeFromServerThrows() {
        tokenBodyTemplate = "{\"errcode\":40001,\"errmsg\":\"invalid corpsecret\"}";
        WeWorkAccessTokenManager m = newManager();
        assertThatThrownBy(m::get)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("errcode=40001");
    }
}
