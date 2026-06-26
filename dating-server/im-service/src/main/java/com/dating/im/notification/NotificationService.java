package com.dating.im.notification;

import com.dating.im.client.OpenImApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates OpenIM business notifications (custom data pushed to a client, delivered via the
 * client's {@code OnRecvCustomBusinessMessage} callback).
 *
 * <p>This is the in-process reusable entry point: im-service's own flows inject this service
 * directly, while external services reach it through the {@code ImService.SendBusinessNotification}
 * gRPC method — neither path loops back through gRPC.
 *
 * <p>Prefer the typed {@link #send(String, NotificationPayload)} overloads: the notification's key,
 * data JSON, persistence and reliability all come from the {@link NotificationPayload} itself, so no
 * caller hand-assembles JSON. The low-level 7-arg {@link #sendBusinessNotification} stays for the
 * generic gRPC entry point. Validation, default values and OpenIM response parsing live here;
 * {@link OpenImApiClient} stays a thin REST wrapper.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final int RELIABILITY_ONLINE_ONLY = 1;

    private final OpenImApiClient openImClient;
    private final ObjectMapper objectMapper;

    /** Default sender for system notifications; falls back to the OpenIM admin user id. */
    @Value("${openim.system-notification-user-id:${openim.admin-user-id:imAdmin}}")
    private String systemNotificationUserId;

    public NotificationService(OpenImApiClient openImClient, ObjectMapper objectMapper) {
        this.openImClient = openImClient;
        this.objectMapper = objectMapper;
    }

    // ---- Typed entry points (preferred): key/data/persist/reliability are payload-described ----

    /** Sends a typed notification to a user; sender defaults to the system account. */
    public NotificationResult send(String recvUserId, NotificationPayload payload) {
        return send(null, recvUserId, payload);
    }

    /** Sends a typed notification to a user as {@code sendUserId} (blank → system account). */
    public NotificationResult send(String sendUserId, String recvUserId, NotificationPayload payload) {
        return sendBusinessNotification(sendUserId, recvUserId, null,
                payload.key(), payload.toData(objectMapper), payload.persist(), payload.reliabilityLevel());
    }

    /** Sends a typed notification to a group; sender defaults to the system account. */
    public NotificationResult sendToGroup(String recvGroupId, NotificationPayload payload) {
        return sendBusinessNotification(null, null, recvGroupId,
                payload.key(), payload.toData(objectMapper), payload.persist(), payload.reliabilityLevel());
    }

    // ---- Low-level entry point (generic gRPC SendBusinessNotification) ----

    /**
     * Sends a business notification.
     *
     * @param sendUserId      sender / system id; blank → {@link #systemNotificationUserId}
     * @param recvUserId      receiver user id; mutually exclusive with {@code recvGroupId}
     * @param recvGroupId     receiver group id; mutually exclusive with {@code recvUserId}
     * @param key             business category (non-blank), e.g. "typing" / "match_success"
     * @param data            business payload (non-blank), agreed to be a JSON string
     * @param sendMsg         also persist as a message
     * @param reliabilityLevel 1=online push (default), 2=guaranteed delivery; ≤0 → 1
     */
    public NotificationResult sendBusinessNotification(String sendUserId, String recvUserId, String recvGroupId,
                                                       String key, String data, boolean sendMsg, int reliabilityLevel) {
        if (key == null || key.isBlank()) {
            return NotificationResult.failure("key is required");
        }
        if (data == null || data.isBlank()) {
            return NotificationResult.failure("data is required");
        }
        boolean hasUser = recvUserId != null && !recvUserId.isBlank();
        boolean hasGroup = recvGroupId != null && !recvGroupId.isBlank();
        if (hasUser == hasGroup) {
            return NotificationResult.failure("exactly one of recvUserId / recvGroupId is required");
        }

        String sender = (sendUserId == null || sendUserId.isBlank()) ? systemNotificationUserId : sendUserId;
        int reliability = reliabilityLevel <= 0 ? RELIABILITY_ONLINE_ONLY : reliabilityLevel;

        JsonNode resp;
        try {
            resp = openImClient.sendBusinessNotification(sender, recvUserId, recvGroupId, key, data, sendMsg, reliability);
        } catch (Exception e) {
            log.error("send_business_notification error: key={} recvUser={} recvGroup={}",
                    key, recvUserId, recvGroupId, e);
            return NotificationResult.failure("internal error: " + e.getMessage());
        }

        int code = resp.path("errCode").asInt(-1);
        if (code != 0) {
            String errMsg = resp.path("errMsg").asText("");
            log.error("send_business_notification failed: key={} recvUser={} recvGroup={} errCode={} errMsg={}",
                    key, recvUserId, recvGroupId, code, errMsg);
            return NotificationResult.failure("openim errCode=" + code + " " + errMsg);
        }

        JsonNode payload = resp.path("data");
        log.info("send_business_notification ok: key={} recvUser={} recvGroup={} clientMsgId={}",
                key, recvUserId, recvGroupId, payload.path("clientMsgID").asText(""));
        return new NotificationResult(true, "ok",
                payload.path("clientMsgID").asText(""),
                payload.path("serverMsgID").asText(""),
                payload.path("sendTime").asLong(0L));
    }

    /** Outcome of a business notification send. */
    public record NotificationResult(boolean success, String message,
                                     String clientMsgId, String serverMsgId, long sendTime) {
        static NotificationResult failure(String message) {
            return new NotificationResult(false, message, "", "", 0L);
        }
    }
}
