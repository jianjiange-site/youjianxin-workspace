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

class WeWorkMessageClientTest {

    private HttpServer server;
    private final AtomicInteger gettokenCalls = new AtomicInteger();
    private final AtomicInteger sendCalls = new AtomicInteger();
    private volatile String sendBody = "{\"errcode\":0,\"errmsg\":\"ok\"}";
    private volatile String firstSendBody;
    private volatile byte[] lastRequestBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> {
            int n = gettokenCalls.incrementAndGet();
            byte[] body = String.format("{\"errcode\":0,\"access_token\":\"TK-%d\",\"expires_in\":7200}", n)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/cgi-bin/message/send", exchange -> {
            int n = sendCalls.incrementAndGet();
            lastRequestBody = exchange.getRequestBody().readAllBytes();
            byte[] body;
            if (n == 1 && firstSendBody != null) {
                body = firstSendBody.getBytes(StandardCharsets.UTF_8);
            } else {
                body = sendBody.getBytes(StandardCharsets.UTF_8);
            }
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

    private WeWorkMessageClient newClient(AlertProperties.WeWork cfg) {
        HttpClient http = HttpClient.newHttpClient();
        WeWorkAccessTokenManager tm = new WeWorkAccessTokenManager(cfg, http);
        return new WeWorkMessageClient(cfg, tm, http);
    }

    private AlertProperties.WeWork baseCfg() {
        AlertProperties.WeWork cfg = new AlertProperties.WeWork();
        cfg.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        cfg.setCorpId("corp-x");
        cfg.setCorpSecret("secret-y");
        cfg.setAgentId(1000001L);
        cfg.setToUser("ZhangSan");
        cfg.setTokenRefreshSkew(Duration.ofSeconds(60));
        cfg.setReadTimeoutMs(5000);
        return cfg;
    }

    @Test
    void successPathPostsMarkdown() throws Exception {
        WeWorkMessageClient c = newClient(baseCfg());
        c.sendMarkdown("# hello");
        assertThat(sendCalls.get()).isEqualTo(1);
        assertThat(gettokenCalls.get()).isEqualTo(1);
        String req = new String(lastRequestBody, StandardCharsets.UTF_8);
        assertThat(req).contains("\"agentid\":1000001");
        assertThat(req).contains("\"touser\":\"ZhangSan\"");
        assertThat(req).contains("\"msgtype\":\"markdown\"");
        assertThat(req).contains("# hello");
    }

    @Test
    void expiredTokenTriggersForceRefreshAndOneRetry() throws Exception {
        firstSendBody = "{\"errcode\":42001,\"errmsg\":\"access_token expired\"}";
        sendBody = "{\"errcode\":0,\"errmsg\":\"ok\"}";
        WeWorkMessageClient c = newClient(baseCfg());
        c.sendMarkdown("# x");
        assertThat(sendCalls.get()).isEqualTo(2);
        assertThat(gettokenCalls.get()).isEqualTo(2); // initial + forceRefresh
    }

    @Test
    void otherErrcodeThrows() {
        sendBody = "{\"errcode\":60011,\"errmsg\":\"no privilege\"}";
        WeWorkMessageClient c = newClient(baseCfg());
        assertThatThrownBy(() -> c.sendMarkdown("# x"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("errcode=60011");
    }

    @Test
    void defaultsToAtAllWhenNoReceiverConfigured() throws Exception {
        AlertProperties.WeWork cfg = baseCfg();
        cfg.setToUser("");
        cfg.setToParty("");
        cfg.setToTag("");
        WeWorkMessageClient c = newClient(cfg);
        c.sendMarkdown("# y");
        String req = new String(lastRequestBody, StandardCharsets.UTF_8);
        assertThat(req).contains("\"touser\":\"@all\"");
    }
}
