package com.dating.im.adaptor;

import com.dating.im.model.ImMessage;
import com.dating.im.model.event.ImEvent;
import com.dating.im.model.event.MessageBeforeSendEvent;
import com.dating.im.model.event.MessageSentEvent;
import com.dating.im.model.event.UnknownEvent;
import com.dating.im.model.event.UserOfflineEvent;
import com.dating.im.model.event.UserOnlineEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dating.youjianxin.proto.im.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Parses OpenIM Server webhook callbacks into {@link ImEvent}, dispatching on {@code callbackCommand}:
 * <ul>
 *   <li>{@code callbackAfterSendSingleMsgCommand}  → {@link MessageSentEvent}</li>
 *   <li>{@code callbackBeforeSendSingleMsgCommand} → {@link MessageBeforeSendEvent}</li>
 *   <li>{@code callbackUserOnlineCommand}          → {@link UserOnlineEvent}</li>
 *   <li>{@code callbackUserOfflineCommand}         → {@link UserOfflineEvent}</li>
 *   <li>anything else                              → {@link UnknownEvent} (logged + acked, not persisted)</li>
 * </ul>
 *
 * <p>Single-msg callback JSON (simplified):
 * <pre>
 * {
 *   "callbackCommand": "callbackAfterSendSingleMsgCommand",
 *   "operationID": "uuid-...",
 *   "serverMsgID": "abc123...",
 *   "clientMsgID": "xyz789...",
 *   "sendID": "user123",
 *   "recvID": "user456",
 *   "contentType": 101,
 *   "content": "hello",
 *   "sendTime": 1717171200000,
 *   "sessionType": 1
 * }
 * </pre>
 *
 * <p>messageId 拼装优先级(2026-05-29 修):
 * <pre>
 *   openim_&lt;serverMsgID&gt;  ← OpenIM 服务端全局唯一,首选
 *   openim_c_&lt;clientMsgID&gt; ← 客户端 ID,无 server ID 时降级
 *   openim_op_&lt;operationID&gt; ← 仍然没有时,callback 自身的 operationID 兜底
 *   openim_ts_&lt;sendTime&gt;_&lt;sendID&gt;_&lt;recvID&gt; ← 终极兜底,极少触发
 * </pre>
 * 旧实现读不存在的 `seq` 字段,messageId 永远是 "openim_0",
 * 导致 chat_messages.message_id PK 冲突,事务回滚,MessageSentHandler 后续
 * BH→DH 分流路径完全没机会执行(DH 永远不回消息)。
 *
 * <p>ContentType mapping:
 * <pre>
 * 101 = text, 102 = image, 103 = audio, 104 = video, 105 = file, 200 = custom
 * </pre>
 */
@Component
public class OpenImAdaptor implements ImProviderAdaptor {

    private static final Logger log = LoggerFactory.getLogger(OpenImAdaptor.class);
    private static final String PROVIDER = "openim";

    static final String CMD_AFTER_SEND_SINGLE = "callbackAfterSendSingleMsgCommand";
    static final String CMD_BEFORE_SEND_SINGLE = "callbackBeforeSendSingleMsgCommand";
    static final String CMD_USER_ONLINE = "callbackUserOnlineCommand";
    static final String CMD_USER_OFFLINE = "callbackUserOfflineCommand";

    /** IMAGE 消息解析出的可公网访问图片 URL,写入 metadata 供下游(VisionAgent)消费。 */
    public static final String METADATA_IMAGE_URL = "image_url";

    private final ObjectMapper objectMapper;

    public OpenImAdaptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String provider) {
        return PROVIDER.equals(provider);
    }

    @Override
    public ImEvent parse(byte[] rawPayload) {
        String raw = new String(rawPayload, StandardCharsets.UTF_8);
        try {
            // TODO(debug): 临时打印原始回调结构,确认 OpenIM 各类型(尤其 IMAGE 的 content/PictureElem)
            //              的实际字段;定位完成后改 DEBUG 或删除。
            log.info("OpenIM raw callback: {}", raw);

            JsonNode root = objectMapper.readTree(rawPayload);
            String cmd = pathText(root, "callbackCommand");

            return switch (cmd) {
                case CMD_AFTER_SEND_SINGLE -> new MessageSentEvent(toMessage(root, cmd), cmd);
                case CMD_BEFORE_SEND_SINGLE -> new MessageBeforeSendEvent(toMessage(root, cmd), cmd);
                case CMD_USER_ONLINE -> toPresence(root, cmd, true);
                case CMD_USER_OFFLINE -> toPresence(root, cmd, false);
                default -> new UnknownEvent(PROVIDER, cmd, raw);
            };
        } catch (IOException e) {
            log.error("Failed to parse OpenIM callback payload", e);
            return new UnknownEvent(PROVIDER, "", raw);
        }
    }

    /** Builds the vendor-agnostic message from a single-msg callback body (after / before send). */
    private ImMessage toMessage(JsonNode root, String cmd) {
        String sendId = pathText(root, "sendID");
        String recvId = pathText(root, "recvID");
        int contentType = root.path("contentType").asInt(101);
        String content = root.path("content").asText("");
        long sendTime = root.path("sendTime").asLong(System.currentTimeMillis());

        MessageType type = mapContentType(contentType);
        String messageId = buildMessageId(root, sendId, recvId, sendTime);

        log.debug("Normalized OpenIM message: msgId={} from={} to={} type={}", messageId, sendId, recvId, type);

        ImMessage.Builder builder = ImMessage.builder()
                .messageId(messageId)
                .fromUserId(sendId)
                .toUserId(recvId)
                .content(content)
                .type(type)
                .conversationType("C2C")
                .provider(PROVIDER)
                .timestamp(sendTime / 1000)
                .putMetadata("server_msg_id", pathText(root, "serverMsgID"))
                .putMetadata("client_msg_id", pathText(root, "clientMsgID"))
                .putMetadata("content_type", String.valueOf(contentType))
                .putMetadata("callback_command", cmd);

        // IMAGE 消息:从 PictureElem 解析出图片 URL,放进 metadata(content 保留原始 elem)。
        if (type == MessageType.IMAGE) {
            String imageUrl = extractImageUrl(root.path("content"));
            if (!imageUrl.isEmpty()) {
                builder.putMetadata(METADATA_IMAGE_URL, imageUrl);
            } else {
                log.warn("IMAGE callback without resolvable image URL: msgId={}", messageId);
            }
        }

        return builder.build();
    }

    /** Builds an online/offline presence event. OpenIM sends {@code userID} + {@code platformID}. */
    private ImEvent toPresence(JsonNode root, String cmd, boolean online) {
        String userId = pathText(root, "userID");
        String platform = pathText(root, "platformID");
        long ts = System.currentTimeMillis();
        return online
                ? new UserOnlineEvent(PROVIDER, cmd, userId, platform, ts)
                : new UserOfflineEvent(PROVIDER, cmd, userId, platform, ts);
    }

    /**
     * 从 OpenIM PictureElem 提取图片 URL。OpenIM 回调里 {@code content} 字段对图片消息是
     * PictureElem(可能是 JSON 字符串,也可能是嵌套对象),结构形如:
     * <pre>{ "sourcePicture": {"url": "..."}, "bigPicture": {...}, "snapshotPicture": {...} }</pre>
     * AI 只需识别图片内容,图越小越省带宽/成本,故优先取缩略图 {@code snapshotPicture.url},
     * 回退大图 {@code bigPicture.url},再回退原图 {@code sourcePicture.url}。
     * 解析失败 / 字段缺失返回空串(不抛异常)。
     */
    private String extractImageUrl(JsonNode contentNode) {
        try {
            JsonNode pic;
            if (contentNode.isObject()) {
                pic = contentNode;
            } else {
                // OpenIM 通常把 elem 作为 JSON 字符串放在 content 里,需二次解析。
                String text = contentNode.asText("");
                if (text.isEmpty()) {
                    return "";
                }
                pic = objectMapper.readTree(text);
            }
            // 由小到大:缩略图 → 大图 → 原图。
            String url = pictureUrl(pic, "snapshotPicture");
            if (url.isEmpty()) url = pictureUrl(pic, "bigPicture");
            if (url.isEmpty()) url = pictureUrl(pic, "sourcePicture");
            return url;
        } catch (IOException e) {
            log.warn("Failed to parse OpenIM image content for URL: {}", e.getMessage());
            return "";
        }
    }

    private static String pictureUrl(JsonNode pic, String field) {
        JsonNode node = pic.path(field).path("url");
        return node.isMissingNode() ? "" : node.asText("");
    }

    // messageId 拼装 —— 按可靠性降级,见类注释。
    // 注意:OpenIM v1.8 callback body 没有 "seq" 字段(旧实现 bug 来源),
    // 必须用 serverMsgID/clientMsgID/operationID 这些真实存在的字段。
    private static String buildMessageId(JsonNode root, String sendId, String recvId, long sendTime) {
        String serverMsgId = pathText(root, "serverMsgID");
        if (!serverMsgId.isEmpty()) {
            return PROVIDER + "_" + serverMsgId;
        }
        String clientMsgId = pathText(root, "clientMsgID");
        if (!clientMsgId.isEmpty()) {
            return PROVIDER + "_c_" + clientMsgId;
        }
        String operationId = pathText(root, "operationID");
        if (!operationId.isEmpty()) {
            return PROVIDER + "_op_" + operationId;
        }
        // 极少触发的兜底:OpenIM 出全空 ID 是不正常情况,但不让流程挂死
        return PROVIDER + "_ts_" + sendTime + "_" + sendId + "_" + recvId;
    }

    private MessageType mapContentType(int contentType) {
        return switch (contentType) {
            case 101 -> MessageType.TEXT;
            case 102 -> MessageType.IMAGE;
            case 103 -> MessageType.AUDIO;
            case 104 -> MessageType.VIDEO;
            case 105 -> MessageType.FILE;
            case 200 -> MessageType.CUSTOM;
            default -> MessageType.MESSAGE_TYPE_UNKNOWN;
        };
    }

    private static String pathText(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isMissingNode() ? "" : node.asText("");
    }
}
