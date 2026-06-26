package com.dating.match.client;

import com.dating.match.constant.OutboxAction;
import com.dating.youjianxin.proto.im.ImServiceGrpc;
import com.dating.youjianxin.proto.im.ListOnlineUserIdsRequest;
import com.dating.youjianxin.proto.im.ListOnlineUserIdsResponse;
import com.dating.youjianxin.proto.im.ListRecentOfflineUsersRequest;
import com.dating.youjianxin.proto.im.ListRecentOfflineUsersResponse;
import com.dating.youjianxin.proto.im.MessageType;
import com.dating.youjianxin.proto.im.SendMessageRequest;
import com.dating.youjianxin.proto.im.SendMessageResponse;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * im-service 客户端封装。
 *
 * <p>不新增 im-service RPC,所有 3 类副作用都用现有 SendMessage(type=CUSTOM) + metadata.action
 * 表达,由 im-service 内部按 action 路由:
 * <ul>
 *   <li>action=ensure_conv → 隐式建会话(SendMessage 本身的副作用,空内容)</li>
 *   <li>action=match_welcome → 双方各一条"你们配对了"系统消息</li>
 *   <li>action=dh_opening → DH 端触发 ai-chat 生成开场白</li>
 * </ul>
 *
 * <p>另外封装 DH 模拟计划用的两个 presence 查询(docs §6.3 / §7.7):
 * <ul>
 *   <li>{@link #listOnlineUserIds}:OnlinePlanGenerator 用,读 im-service Redis ZSet</li>
 *   <li>{@link #listRecentOfflineUsers}:OfflinePlanGenerator 用,读 im-service PG user_online_session</li>
 * </ul>
 *
 * <p>详见 dating-server/docs/match-service-prd-tech.md §6.7。
 */
@Slf4j
@Component
public class ImClient {

    private static final long CALL_TIMEOUT_MS = 3000L;
    /** presence 查询走 PG / Redis,服务端有 limit 50000 上限保护;留 5s 余量给慢查询 */
    private static final long PRESENCE_TIMEOUT_MS = 5000L;
    private static final String PROVIDER = "openim";
    private static final String CONVERSATION_C2C = "C2C";
    private static final String SOURCE = "match-service";

    @GrpcClient("im-service")
    private ImServiceGrpc.ImServiceBlockingStub stub;

    /** 建/确认 C2C 会话(对应 outbox.action = ENSURE_CONVERSATION) */
    public boolean ensureConversation(long userA, long userB, long matchId) {
        return sendCustom(userA, userB, "", "ensure_conv", matchId, OutboxAction.ENSURE_CONVERSATION);
    }

    /** 给 receiverUserId 发"你们配对了"系统消息(对应 outbox.action = SYSTEM_MSG) */
    public boolean sendMatchWelcome(long fromUserId, long receiverUserId, long matchId, String text) {
        return sendCustom(fromUserId, receiverUserId, text == null ? "你们配对了" : text,
                "match_welcome", matchId, OutboxAction.SYSTEM_MSG);
    }

    /** 触发 DH 端 ai-chat 开场白(对应 outbox.action = DH_OPENING) */
    public boolean triggerDhOpening(long dhUserId, long bhUserId, long matchId) {
        return sendCustom(dhUserId, bhUserId, "", "dh_opening", matchId, OutboxAction.DH_OPENING);
    }

    /**
     * 取 [sinceMs, untilMs] 区间内新建立 OpenIM 会话(score = online_at)的真人 userId 列表。
     * 失败 fail-open 返回空列表,scheduler 当成"本轮无新上线"处理,游标不推进。
     *
     * @param limit 单次返回上限;服务端硬上限 50000,0/负数走默认 5000
     */
    public List<Long> listOnlineUserIds(long sinceMs, long untilMs, int limit) {
        try {
            ListOnlineUserIdsResponse resp = stub
                    .withDeadlineAfter(PRESENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .listOnlineUserIds(ListOnlineUserIdsRequest.newBuilder()
                            .setSinceMs(sinceMs)
                            .setUntilMs(untilMs)
                            .setLimit(limit)
                            .build());
            return resp.getUserIdsList();
        } catch (StatusRuntimeException sre) {
            log.warn("listOnlineUserIds failed since={} until={} status={}",
                    sinceMs, untilMs, sre.getStatus());
            return Collections.emptyList();
        }
    }

    /**
     * 取 [sinceMs, untilMs] 区间内已"OpenIM 下线回调收口"的真人 userId 列表。
     * 失败 fail-open 返回空列表。
     */
    public List<Long> listRecentOfflineUsers(long sinceMs, long untilMs, int limit) {
        try {
            ListRecentOfflineUsersResponse resp = stub
                    .withDeadlineAfter(PRESENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .listRecentOfflineUsers(ListRecentOfflineUsersRequest.newBuilder()
                            .setSinceMs(sinceMs)
                            .setUntilMs(untilMs)
                            .setLimit(limit)
                            .build());
            return resp.getUserIdsList();
        } catch (StatusRuntimeException sre) {
            log.warn("listRecentOfflineUsers failed since={} until={} status={}",
                    sinceMs, untilMs, sre.getStatus());
            return Collections.emptyList();
        }
    }

    private boolean sendCustom(long fromUserId, long toUserId, String content,
                               String action, long matchId, String outboxAction) {
        Map<String, String> meta = new HashMap<>(4);
        meta.put("source", SOURCE);
        meta.put("action", action);
        meta.put("outbox_action", outboxAction);
        meta.put("match_id", String.valueOf(matchId));

        SendMessageRequest req = SendMessageRequest.newBuilder()
                .setFromUserId(String.valueOf(fromUserId))
                .setToUserId(String.valueOf(toUserId))
                .setContent(content == null ? "" : content)
                .setType(MessageType.CUSTOM)
                .setConversationType(CONVERSATION_C2C)
                .setProvider(PROVIDER)
                .putAllMetadata(meta)
                .build();
        try {
            SendMessageResponse resp = stub
                    .withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .sendMessage(req);
            if (!resp.getSuccess()) {
                log.warn("im sendMessage action={} matchId={} success=false msg={}",
                        action, matchId, resp.getMessage());
                return false;
            }
            return true;
        } catch (StatusRuntimeException sre) {
            log.error("im sendMessage action={} matchId={} status={}", action, matchId, sre.getStatus(), sre);
            throw sre;   // 让 outbox 调用方走 retry 链路
        }
    }
}
