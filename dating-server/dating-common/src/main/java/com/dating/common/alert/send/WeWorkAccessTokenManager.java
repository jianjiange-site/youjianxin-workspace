package com.dating.common.alert.send;

import com.dating.common.alert.AlertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// access_token 本地缓存 + 提前 skew 刷新 + 并发去重(double-check synchronized) + forceRefresh 强刷。
// gettoken 接口同 corp 限 200/天 — 必须严格缓存,不能每条告警都刷。
public class WeWorkAccessTokenManager {

    private static final Logger log = LoggerFactory.getLogger(WeWorkAccessTokenManager.class);

    private static final Pattern P_ERRCODE = Pattern.compile("\"errcode\"\\s*:\\s*(-?\\d+)");
    private static final Pattern P_ERRMSG = Pattern.compile("\"errmsg\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern P_ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_EXPIRES_IN = Pattern.compile("\"expires_in\"\\s*:\\s*(\\d+)");

    private final HttpClient http;
    private final String baseUrl;
    private final String corpId;
    private final String corpSecret;
    private final long refreshSkewNanos;
    private final Duration readTimeout;

    private final Object refreshLock = new Object();
    private volatile CachedToken current;

    private record CachedToken(String token, long expireAtNanos) {}

    public WeWorkAccessTokenManager(AlertProperties.WeWork cfg, HttpClient http) {
        this.http = http;
        this.baseUrl = cfg.getBaseUrl();
        this.corpId = cfg.getCorpId();
        this.corpSecret = cfg.getCorpSecret();
        this.refreshSkewNanos = cfg.getTokenRefreshSkew().toNanos();
        this.readTimeout = Duration.ofMillis(cfg.getReadTimeoutMs());
    }

    public String get() throws IOException, InterruptedException {
        CachedToken t = current;
        long now = System.nanoTime();
        if (t != null && now < t.expireAtNanos - refreshSkewNanos) return t.token;
        synchronized (refreshLock) {
            t = current;
            now = System.nanoTime();
            if (t != null && now < t.expireAtNanos - refreshSkewNanos) return t.token;
            return doRefresh();
        }
    }

    public String forceRefresh() throws IOException, InterruptedException {
        synchronized (refreshLock) {
            return doRefresh();
        }
    }

    private String doRefresh() throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/cgi-bin/gettoken"
                + "?corpid=" + urlEnc(corpId)
                + "&corpsecret=" + urlEnc(corpSecret));
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(readTimeout).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("gettoken http " + resp.statusCode());
        }
        String body = resp.body();
        int errcode = parseErrcode(body);
        if (errcode != 0) {
            throw new IOException("gettoken errcode=" + errcode + " errmsg=" + parseErrmsg(body));
        }
        String token = parseAccessToken(body);
        long expiresInSec = parseExpiresIn(body);
        if (expiresInSec <= 0) expiresInSec = 7200;
        long expireAtNanos = System.nanoTime() + expiresInSec * 1_000_000_000L;
        current = new CachedToken(token, expireAtNanos);
        return token;
    }

    static int parseErrcode(String body) {
        if (body == null) return -1;
        Matcher m = P_ERRCODE.matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static String parseErrmsg(String body) {
        if (body == null) return "";
        Matcher m = P_ERRMSG.matcher(body);
        return m.find() ? m.group(1) : "";
    }

    static String parseAccessToken(String body) {
        Matcher m = P_ACCESS_TOKEN.matcher(body);
        if (!m.find()) throw new IllegalStateException("access_token not found in response: " + body);
        return m.group(1);
    }

    static long parseExpiresIn(String body) {
        if (body == null) return 0;
        Matcher m = P_EXPIRES_IN.matcher(body);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static String urlEnc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
