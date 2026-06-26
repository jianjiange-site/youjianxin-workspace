package com.dating.im.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps OpenIM Server REST API (port 10002).
 *
 * <p>Reference: https://docs.openim.io/restapi/apis/introduction
 */
@Component
public class OpenImApiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenImApiClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${openim.api-url:http://127.0.0.1:10002}")
    private String apiUrl;

    @Value("${openim.admin-user-id:imAdmin}")
    private String adminUserId;

    @Value("${openim.admin-secret:}")
    private String adminSecret;

    private volatile String adminToken;
    private volatile long adminTokenExpiresAt;

    public OpenImApiClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    // ---- Admin Token ----

    public String getAdminToken() {
        if (adminToken != null && System.currentTimeMillis() < adminTokenExpiresAt) {
            return adminToken;
        }
        synchronized (this) {
            if (adminToken != null && System.currentTimeMillis() < adminTokenExpiresAt) {
                return adminToken;
            }
            refreshAdminToken();
            return adminToken;
        }
    }

    private void refreshAdminToken() {
        Map<String, Object> body = Map.of(
                "secret", adminSecret,
                "userID", adminUserId
        );
        JsonNode resp = post("/auth/get_admin_token", body);
        adminToken = resp.path("data").path("token").asText();
        long expiresIn = resp.path("data").path("expireTimeSeconds").asLong(3600);
        adminTokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000) - 60_000;
        log.info("Admin token refreshed, expires in {}s", expiresIn);
    }

    // ---- User Token (签发普通用户的 IM Token) ----

    public String getUserToken(String userId, int platform) {
        Map<String, Object> body = new HashMap<>();
        body.put("secret", adminSecret);
        body.put("userID", userId);
        if (platform > 0) {
            body.put("platformID", platform);
        }
        JsonNode resp = adminPost("/auth/get_user_token", body);
        String token = resp.path("data").path("token").asText();
        log.debug("User token generated: userId={}, platform={}", userId, platform);
        return token;
    }

    // ---- User Registration ----

    public boolean registerUser(String userId, String nickname, String faceUrl) {
        Map<String, Object> body = Map.of(
                "users", List.of(Map.of(
                        "userID", userId,
                        "nickname", nickname,
                        "faceURL", faceUrl != null ? faceUrl : ""
                ))
        );
        JsonNode resp = adminPost("/user/user_register", body);
        int code = resp.path("errCode").asInt(-1);
        if (code == 0) {
            log.info("Register IM user: userId={} nickname={} errCode=0", userId, nickname);
            return true;
        }
        // 幂等:OpenIM 各版本"已注册"errCode 不一,按 errMsg/errDlt 文案兜底识别,视作成功(不告警)。
        String detail = (resp.path("errMsg").asText("") + " " + resp.path("errDlt").asText(""))
                .toLowerCase();
        if (detail.contains("registered") || detail.contains("exist")) {
            log.info("IM user already registered (idempotent): userId={} errCode={}", userId, code);
            return true;
        }
        log.error("Register IM user failed: userId={} errCode={} errMsg={} errDlt={}",
                userId, code, resp.path("errMsg").asText(), resp.path("errDlt").asText());
        return false;
    }

    // ---- Send Message ----

    public boolean sendMsg(String fromUserId, String toUserId, String textContent) {
        Map<String, Object> body = Map.of(
                "sendID", fromUserId,
                "recvID", toUserId,
                "contentType", 101,
                "content", Map.of("content", textContent),
                "sessionType", 1
        );
        JsonNode resp = adminPost("/msg/send_msg", body);
        int code = resp.path("errCode").asInt(-1);
        if (code != 0) {
            log.error("OpenIM send_msg failed: errCode={} errMsg={}", code, resp.path("errMsg").asText());
            return false;
        }
        return true;
    }

    // ---- Business Notification ----

    /**
     * OpenIM {@code POST /msg/send_business_notification}: pushes a custom business notification;
     * clients receive it via the {@code OnRecvCustomBusinessMessage} callback.
     *
     * <p>{@code recvUserId} / {@code recvGroupId} are mutually exclusive — pass exactly one
     * (validation is done in the service layer). Returns the raw OpenIM response JSON.
     */
    public JsonNode sendBusinessNotification(String sendUserId, String recvUserId, String recvGroupId,
                                             String key, String data, boolean sendMsg, int reliabilityLevel) {
        Map<String, Object> body = new HashMap<>();
        body.put("sendUserID", sendUserId);
        if (recvUserId != null && !recvUserId.isBlank()) {
            body.put("recvUserID", recvUserId);
        }
        if (recvGroupId != null && !recvGroupId.isBlank()) {
            body.put("recvGroupID", recvGroupId);
        }
        body.put("key", key);
        body.put("data", data);
        body.put("sendMsg", sendMsg);
        body.put("reliabilityLevel", reliabilityLevel);
        return adminPost("/msg/send_business_notification", body);
    }

    // ---- Search Messages ----

    public JsonNode searchMsg(String userId1, String userId2, long startTime, long endTime, int pageSize) {
        Map<String, Object> body = Map.of(
                "sendID", userId1,
                "recvID", userId2,
                "contentType", 0,          // 0 = all types
                "sessionType", 1,
                "startTime", startTime,
                "endTime", endTime,
                "pagination", Map.of(
                        "pageNumber", 1,
                        "showNumber", pageSize
                )
        );
        return adminPost("/msg/search_msg", body);
    }

    // ---- Friend Management ----

    public boolean addFriend(String fromUserId, String toUserId) {
        Map<String, Object> body = Map.of(
                "fromUserID", fromUserId,
                "toUserID", toUserId
        );
        JsonNode resp = adminPost("/friend/add_friend", body);
        return resp.path("errCode").asInt(-1) == 0;
    }

    // ---- HTTP helpers ----

    private JsonNode adminPost(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("token", getAdminToken());
        headers.set("operationID", String.valueOf(System.currentTimeMillis()));
        return doPost(path, body, headers);
    }

    private JsonNode post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // OpenIM 要求所有 REST 调用必须带 operationID,缺则 ArgsError;非 admin 路径(如 /auth/get_admin_token)同样必须。
        headers.set("operationID", String.valueOf(System.currentTimeMillis()));
        return doPost(path, body, headers);
    }

    private JsonNode doPost(String path, Map<String, Object> body, HttpHeaders headers) {
        String url = apiUrl + path;
        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.error("OpenIM API error: POST {} body={}", url, body, e);
            return objectMapper.createObjectNode().put("errCode", -1).put("errMsg", e.getMessage());
        }
    }
}
