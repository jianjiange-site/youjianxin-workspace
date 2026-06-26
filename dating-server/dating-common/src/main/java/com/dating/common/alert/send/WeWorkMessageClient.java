package com.dating.common.alert.send;

import com.dating.common.alert.AlertProperties;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

// 调用企业微信 /cgi-bin/message/send。errcode=42001/40014(token 失效)时 forceRefresh 重试一次,不再重试。
public class WeWorkMessageClient {

    private static final int ERRCODE_OK = 0;
    private static final int ERRCODE_TOKEN_EXPIRED = 42001;
    private static final int ERRCODE_TOKEN_INVALID = 40014;

    private final HttpClient http;
    private final WeWorkAccessTokenManager tokenManager;
    private final AlertProperties.WeWork cfg;
    private final Duration readTimeout;

    public WeWorkMessageClient(AlertProperties.WeWork cfg, WeWorkAccessTokenManager tokenManager, HttpClient http) {
        this.cfg = cfg;
        this.tokenManager = tokenManager;
        this.http = http;
        this.readTimeout = Duration.ofMillis(cfg.getReadTimeoutMs());
    }

    public void sendMarkdown(String markdownContent) throws IOException, InterruptedException {
        String token = tokenManager.get();
        int code = doPost(token, markdownContent);
        if (code == ERRCODE_TOKEN_EXPIRED || code == ERRCODE_TOKEN_INVALID) {
            String fresh = tokenManager.forceRefresh();
            code = doPost(fresh, markdownContent);
        }
        if (code != ERRCODE_OK) {
            throw new IOException("message/send errcode=" + code);
        }
    }

    private int doPost(String token, String markdownContent) throws IOException, InterruptedException {
        String body = buildPayload(markdownContent);
        URI uri = URI.create(cfg.getBaseUrl() + "/cgi-bin/message/send?access_token=" + urlEnc(token));
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(readTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("message/send http " + resp.statusCode());
        }
        return WeWorkAccessTokenManager.parseErrcode(resp.body());
    }

    String buildPayload(String markdownContent) {
        String toUser = cfg.getToUser();
        String toParty = cfg.getToParty();
        String toTag = cfg.getToTag();
        if (isBlank(toUser) && isBlank(toParty) && isBlank(toTag)) {
            toUser = "@all";
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"touser\":\"").append(jsonString(toUser)).append("\",");
        sb.append("\"toparty\":\"").append(jsonString(toParty)).append("\",");
        sb.append("\"totag\":\"").append(jsonString(toTag)).append("\",");
        sb.append("\"msgtype\":\"markdown\",");
        sb.append("\"agentid\":").append(cfg.getAgentId()).append(',');
        sb.append("\"markdown\":{\"content\":\"").append(jsonString(markdownContent)).append("\"}");
        sb.append('}');
        return sb.toString();
    }

    static String jsonString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String urlEnc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
