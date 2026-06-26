package com.dating.im.handler;

import com.dating.im.adaptor.OpenImAdaptor;
import com.dating.im.client.AiChatGrpcClient;
import com.dating.im.client.UserProfileGrpcClient;
import com.dating.im.client.VisionAgentGrpcClient;
import com.dating.im.model.ImMessage;
import com.dating.im.model.event.MessageSentEvent;
import com.dating.im.recorder.MessageRecorder;
import com.dating.im.util.UserIdUtils;
import com.dating.youjianxin.proto.im.MessageType;
import com.dating.youjianxin.proto.user.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles a sent-message event ({@link MessageSentEvent}): classifies both ends by user type and
 * routes accordingly.
 *
 * <ul>
 *   <li>BH + BH — record only (human-to-human)</li>
 *   <li>BH + DH — call ai-chat, then send AI reply via {@link com.dating.im.sender.MessageSender}</li>
 *   <li>DH + BH — record only (AI reply already sent, or system message)</li>
 *   <li>DH + DH — anomaly, record and skip</li>
 * </ul>
 *
 * user_type 取自 user-service.UserProfileService.batchGetProfile;一次 RPC 拿
 * (from, to) 两端,user-service 端本身有 Redis cache(user:profile:*),im-service
 * 不再二级缓存。无法解析 / RPC 失败 / 用户不存在均回退 BH(record-only 安全)。
 */
@Component
public class MessageSentHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageSentHandler.class);

    /** Image placeholder + description prefix fed to ai-chat (IMAGE turned into text via VisionAgent.Understand). */
    private static final String UNDERSTAND_PROMPT = "Describe the main content of this image in one concise sentence.";
    private static final String IMAGE_PREFIX = "[Image] ";
    /**
     * Fed to ai-chat when an IMAGE can't be turned into text (image_url missing, or VisionAgent.Understand
     * fails / returns empty). Keeps the [Image] marker so the DH still knows an image was sent, but tells the
     * LLM its content is unavailable (broken / failed to send) so it can react gracefully — e.g. ask the user
     * to resend — instead of pretending to see an image it never actually received.
     */
    private static final String IMAGE_BROKEN_PLACEHOLDER =
            "[Image] (the user sent an image, but it couldn't be loaded - it may be broken or failed to send)";

    private final MessageRecorder recorder;
    private final AiChatGrpcClient aiChatClient;
    private final VisionAgentGrpcClient visionAgentClient;
    private final UserProfileGrpcClient userProfileClient;
    private final AiReplyDispatcher aiReplyDispatcher;
    private final DhTypingEmitter dhTypingEmitter;

    public MessageSentHandler(MessageRecorder recorder,
                              AiChatGrpcClient aiChatClient,
                              VisionAgentGrpcClient visionAgentClient,
                              UserProfileGrpcClient userProfileClient,
                              AiReplyDispatcher aiReplyDispatcher,
                              DhTypingEmitter dhTypingEmitter) {
        this.recorder = recorder;
        this.aiChatClient = aiChatClient;
        this.visionAgentClient = visionAgentClient;
        this.userProfileClient = userProfileClient;
        this.aiReplyDispatcher = aiReplyDispatcher;
        this.dhTypingEmitter = dhTypingEmitter;
    }

    /** Entry point: handle a sent-message event. */
    public void handle(MessageSentEvent event) {
        ImMessage msg = event.message();

        // 1. Determine user types (one batched RPC for both ends)
        Map<Long, UserType> types = lookup(msg.fromUserId(), msg.toUserId());
        boolean fromIsDH = isDH(types, msg.fromUserId());
        boolean toIsDH = isDH(types, msg.toUserId());
        String routeType = routeType(fromIsDH, toIsDH);

        // 2. Record message regardless of type
        recorder.save(msg, routeType);
        log.info("Routed: msgId={} route={} from={} to={}", msg.messageId(), routeType,
                msg.fromUserId(), msg.toUserId());

        // 3. Route
        switch (routeType) {
            case "BH_DH":
                handleBhToDh(msg);
                break;
            case "BH_BH":
            case "DH_BH":
            case "DH_DH":
            default:
                // record only — no further action
                break;
        }
    }

    private void handleBhToDh(ImMessage msg) {
        // 仅 TEXT / IMAGE 触发 AI 回复;音视频/文件/自定义消息只记录不回。
        MessageType type = msg.type();
        if (type != MessageType.TEXT && type != MessageType.IMAGE) {
            log.info("Skip AI reply: msgId={} type={} not in [TEXT, IMAGE]",
                    msg.messageId(), type);
            return;
        }

        String threadId = msg.fromUserId() + ":" + msg.toUserId();

        log.info("Calling ai-chat: threadId={} from={} to={} type={}", threadId,
                msg.fromUserId(), msg.toUserId(), type);

        String chatMessage = resolveChatMessage(msg);
        // AI 生成期间,以 DH(msg.toUserId) 名义向 BH(msg.fromUserId) 持续下发 typing;
        // try-with-resources 保证 chat() 正常返回 / 抛异常都会停发(见 DhTypingEmitter)。
        String aiReply;
        try (DhTypingEmitter.Handle ignored = dhTypingEmitter.start(msg.toUserId(), msg.fromUserId())) {
            aiReply = aiChatClient.chat(threadId, msg.fromUserId(), msg.toUserId(), chatMessage);
        }

        if (aiReply != null && !aiReply.isEmpty()) {
            // 按句末标点切成多条,异步按真人打字节奏分别发出 + 记录(见 AiReplyDispatcher)。
            aiReplyDispatcher.dispatch(msg, aiReply);
        }
    }

    /**
     * 把消息转成喂给 ai-chat 的文本。TEXT 直接用原文;IMAGE 先经 VisionAgent.Understand 得到
     * 图片描述,拼成 "[Image] &lt;描述&gt;";图片 URL 缺失或理解失败时回退 IMAGE_BROKEN_PLACEHOLDER
     * (保留 [Image] 标记并提示图损坏/没发成功),保证 DH 仍能回复、对话不中断,且 LLM 知道有张它看不到的图。
     */
    private String resolveChatMessage(ImMessage msg) {
        if (msg.type() != MessageType.IMAGE) {
            return msg.content();
        }
        String imageUrl = msg.metadataOrDefault(OpenImAdaptor.METADATA_IMAGE_URL, "");
        if (imageUrl.isEmpty()) {
            log.warn("IMAGE without image_url metadata, fallback broken-image placeholder: msgId={}", msg.messageId());
            return IMAGE_BROKEN_PLACEHOLDER;
        }
        String description = visionAgentClient.understand(List.of(imageUrl), null);
        if (description == null || description.isEmpty()) {
            log.warn("VisionAgent.understand failed/empty, fallback broken-image placeholder: msgId={}", msg.messageId());
            return IMAGE_BROKEN_PLACEHOLDER;
        }
        log.info("Image understood: msgId={} descLength={}", msg.messageId(), description.length());
        return IMAGE_PREFIX + description;
    }

    /** Batched lookup; non-numeric or duplicated ids dedup naturally via Map. */
    private Map<Long, UserType> lookup(String fromUserId, String toUserId) {
        List<Long> ids = new ArrayList<>(2);
        Long fromId = UserIdUtils.parseLong(fromUserId);
        Long toId = UserIdUtils.parseLong(toUserId);
        if (fromId != null) ids.add(fromId);
        if (toId != null && !toId.equals(fromId)) ids.add(toId);
        if (ids.isEmpty()) return Map.of();
        return userProfileClient.batchGetUserType(ids);
    }

    private static boolean isDH(Map<Long, UserType> types, String userId) {
        Long id = UserIdUtils.parseLong(userId);
        if (id == null) return false;
        // 缺失 = 用户不存在 / RPC 失败,回退 BH(record-only 路径安全)
        return types.get(id) == UserType.USER_TYPE_DH;
    }

    private static String routeType(boolean fromIsDH, boolean toIsDH) {
        if (!fromIsDH && !toIsDH) return "BH_BH";
        if (!fromIsDH) return "BH_DH";       // BH → DH
        if (!toIsDH) return "DH_BH";         // DH → BH
        return "DH_DH";
    }
}
