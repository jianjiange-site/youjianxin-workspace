package com.dating.im.grpc;

import com.dating.im.client.LiveKitTokenGenerator;
import com.dating.im.client.OpenImApiClient;
import com.dating.im.manager.PresenceRedisManager;
import com.dating.im.model.CallbackResult;
import com.dating.im.model.ImMessage;
import com.dating.im.recorder.OnlineSessionRecorder;
import com.dating.im.sender.MessageSender;
import com.dating.im.service.CallbackService;
import com.dating.im.notification.MatchSuccessNotifier;
import com.dating.im.notification.NotificationService;
import com.dating.youjianxin.proto.im.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@GrpcService
public class ImGrpcService extends ImServiceGrpc.ImServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ImGrpcService.class);

    private final CallbackService callbackService;
    private final List<MessageSender> senders;
    private final OpenImApiClient openImClient;
    private final LiveKitTokenGenerator liveKitTokenGen;
    private final NotificationService notificationService;
    private final MatchSuccessNotifier matchSuccessNotifier;
    private final PresenceRedisManager presenceRedis;
    private final OnlineSessionRecorder onlineSessionRecorder;

    public ImGrpcService(CallbackService callbackService,
                         List<MessageSender> senders,
                         OpenImApiClient openImClient,
                         LiveKitTokenGenerator liveKitTokenGen,
                         NotificationService notificationService,
                         MatchSuccessNotifier matchSuccessNotifier,
                         PresenceRedisManager presenceRedis,
                         OnlineSessionRecorder onlineSessionRecorder) {
        this.callbackService = callbackService;
        this.senders = senders;
        this.openImClient = openImClient;
        this.liveKitTokenGen = liveKitTokenGen;
        this.notificationService = notificationService;
        this.matchSuccessNotifier = matchSuccessNotifier;
        this.presenceRedis = presenceRedis;
        this.onlineSessionRecorder = onlineSessionRecorder;
    }

    // ---- 入站：接收网关透传的原始回调 ----

    @Override
    public void onRawCallback(RawCallback request, StreamObserver<OnRawCallbackResponse> responseObserver) {
        log.info("Received raw callback: provider={}", request.getProvider());

        try {
            CallbackResult result = callbackService.handleCallback(
                    request.getProvider(),
                    request.getPayload().toByteArray());
            responseObserver.onNext(OnRawCallbackResponse.newBuilder()
                    .setSuccess(result.success())
                    .setCode(result.code())
                    .setMessage(result.message())
                    .build());
        } catch (Exception e) {
            log.error("Failed to process callback: provider={}", request.getProvider(), e);
            responseObserver.onNext(OnRawCallbackResponse.newBuilder()
                    .setSuccess(false)
                    .setCode(0)
                    .setMessage("internal error: " + e.getMessage())
                    .build());
        }
        responseObserver.onCompleted();
    }

    // ---- 出站：发送消息 ----

    @Override
    public void sendMessage(SendMessageRequest request, StreamObserver<SendMessageResponse> responseObserver) {
        log.info("SendMessage: from={} to={} provider={}", request.getFromUserId(), request.getToUserId(), request.getProvider());

        MessageSender sender = senders.stream()
                .filter(s -> s.supports(request.getProvider()))
                .findFirst()
                .orElse(null);

        if (sender == null) {
            responseObserver.onNext(SendMessageResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("unsupported provider: " + request.getProvider())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        String messageId = request.getProvider() + "_" + System.currentTimeMillis();
        ImMessage msg = ImMessage.builder()
                .messageId(messageId)
                .fromUserId(request.getFromUserId())
                .toUserId(request.getToUserId())
                .content(request.getContent())
                .type(request.getType())
                .conversationType(request.getConversationType())
                .provider(request.getProvider())
                .putAllMetadata(request.getMetadataMap())
                .timestamp(System.currentTimeMillis() / 1000)
                .build();

        boolean sent = sender.send(msg);
        responseObserver.onNext(SendMessageResponse.newBuilder()
                .setSuccess(sent)
                .setMessage(sent ? "ok" : "send failed")
                .setMessageId(sent ? messageId : "")
                .build());
        responseObserver.onCompleted();
    }

    // ---- Token / 注册 ----

    @Override
    public void getImToken(GetImTokenRequest request, StreamObserver<GetImTokenResponse> responseObserver) {
        String userId = request.getUserId();
        int platform = request.getPlatform();
        log.info("getImToken: userId={}, platform={}", userId, platform);

        String token = "";
        try {
            token = openImClient.getUserToken(userId, platform);
            if (token == null || token.isBlank()) {
                // 兜底:eager 建号失败/漏建,补建后重试一次(registerUser 幂等)。
                // nickname 必须非空,OpenIM 强校验;沿用 mobile-gateway eager-register 的占位格式 user_{userId},
                // 真实昵称由 onboarding 完成后再覆盖。
                log.warn("getImToken empty, lazy-register then retry: userId={}", userId);
                openImClient.registerUser(userId, "user_" + userId, "");
                token = openImClient.getUserToken(userId, platform);
            }
        } catch (Exception e) {
            log.error("getImToken failed: userId={}", userId, e);
        }
        if (token == null || token.isBlank()) {
            log.error("IM token 签发失败 userId={} (OpenIM 不可用或建号失败)", userId);
            token = "";
        }
        responseObserver.onNext(GetImTokenResponse.newBuilder()
                .setUserId(userId)
                .setImToken(token)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void registerImUser(RegisterImUserRequest request, StreamObserver<RegisterImUserResponse> responseObserver) {
        String userId = request.getUserId();
        String nickname = request.getNickname();
        String avatarUrl = request.getAvatarUrl();
        log.info("registerImUser: userId={} nickname={}", userId, nickname);

        try {
            boolean ok = openImClient.registerUser(userId, nickname, avatarUrl);
            responseObserver.onNext(RegisterImUserResponse.newBuilder()
                    .setSuccess(ok)
                    .setUserId(userId)
                    .build());
        } catch (Exception e) {
            log.error("registerImUser failed: userId={}", userId, e);
            responseObserver.onNext(RegisterImUserResponse.newBuilder()
                    .setSuccess(false)
                    .setUserId(userId)
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void generateCallToken(GenerateCallTokenRequest request, StreamObserver<GenerateCallTokenResponse> responseObserver) {
        String userId = request.getUserId();
        String peerId = request.getPeerId();
        log.info("generateCallToken: userId={} peerId={}", userId, peerId);

        try {
            String token = liveKitTokenGen.generateForCall(userId, peerId);
            responseObserver.onNext(GenerateCallTokenResponse.newBuilder()
                    .setToken(token)
                    .build());
        } catch (Exception e) {
            log.error("generateCallToken failed: userId={} peerId={}", userId, e);
            responseObserver.onNext(GenerateCallTokenResponse.newBuilder()
                    .setToken("")
                    .build());
        }
        responseObserver.onCompleted();
    }

    // ---- 业务通知:推送自定义业务数据给客户端 (OnRecvCustomBusinessMessage) ----

    @Override
    public void sendBusinessNotification(SendBusinessNotificationRequest request,
                                         StreamObserver<SendBusinessNotificationResponse> responseObserver) {
        log.info("sendBusinessNotification: key={} recvUser={} recvGroup={}",
                request.getKey(), request.getRecvUserId(), request.getRecvGroupId());

        NotificationService.NotificationResult result;
        try {
            result = notificationService.sendBusinessNotification(
                    request.getSendUserId(),
                    request.getRecvUserId(),
                    request.getRecvGroupId(),
                    request.getKey(),
                    request.getData(),
                    request.getSendMsg(),
                    request.getReliabilityLevel());
        } catch (Exception e) {
            log.error("sendBusinessNotification failed: key={}", request.getKey(), e);
            result = new NotificationService.NotificationResult(
                    false, "internal error: " + e.getMessage(), "", "", 0L);
        }

        responseObserver.onNext(SendBusinessNotificationResponse.newBuilder()
                .setSuccess(result.success())
                .setMessage(result.message())
                .setClientMsgId(result.clientMsgId())
                .setServerMsgId(result.serverMsgId())
                .setSendTime(result.sendTime())
                .build());
        responseObserver.onCompleted();
    }

    // ---- 匹配成功:一次调用给双方下发 match_success 信号 (DH 跳过) ----

    @Override
    public void sendMatchSuccess(SendMatchSuccessRequest request,
                                 StreamObserver<SendMatchSuccessResponse> responseObserver) {
        MatchParticipant a = request.getParticipantA();
        MatchParticipant b = request.getParticipantB();
        log.info("sendMatchSuccess: matchId={} a={} b={}",
                request.getMatchId(), a.getUserId(), b.getUserId());

        MatchSuccessNotifier.Result result;
        try {
            if (request.getMatchId().isBlank() || a.getUserId().isBlank() || b.getUserId().isBlank()) {
                result = new MatchSuccessNotifier.Result(
                        false, "matchId and both participant userIds are required");
            } else {
                result = matchSuccessNotifier.notifyMatchSuccess(
                        request.getMatchId(), toParticipant(a), toParticipant(b), request.getMatchedAt());
            }
        } catch (Exception e) {
            log.error("sendMatchSuccess failed: matchId={}", request.getMatchId(), e);
            result = new MatchSuccessNotifier.Result(false, "internal error: " + e.getMessage());
        }

        responseObserver.onNext(SendMatchSuccessResponse.newBuilder()
                .setSuccess(result.success())
                .setMessage(result.message())
                .build());
        responseObserver.onCompleted();
    }

    private static MatchSuccessNotifier.Participant toParticipant(MatchParticipant p) {
        return new MatchSuccessNotifier.Participant(
                p.getUserId(), p.getNickname(), p.getAvatarKey(), p.getAge(), p.getIsDh());
    }

    // ---- Presence 查询(DH 模拟计划用)----
    // 信号源由 OpenIM 上下线回调被动维护:在线态在 ZSet im:presence:online,历史态在
    // PG user_online_session。所有跨服务消费必须走这两个 RPC,禁止直读 Redis / PG。
    // 详见 dating-server/docs/match-service-prd-tech.md §6.3.1 / §6.3.2 / §7.7。

    @Override
    public void listOnlineUserIds(ListOnlineUserIdsRequest request,
                                  StreamObserver<ListOnlineUserIdsResponse> responseObserver) {
        long since = request.getSinceMs();
        long until = request.getUntilMs();
        int limit = request.getLimit();
        if (until < since) {
            log.warn("listOnlineUserIds invalid window: since={} until={}", since, until);
            responseObserver.onNext(ListOnlineUserIdsResponse.getDefaultInstance());
            responseObserver.onCompleted();
            return;
        }
        List<Long> userIds;
        try {
            userIds = presenceRedis.rangeByScore(since, until, limit);
        } catch (Exception e) {
            log.error("listOnlineUserIds failed: since={} until={} limit={}", since, until, limit, e);
            userIds = List.of();
        }
        responseObserver.onNext(ListOnlineUserIdsResponse.newBuilder()
                .addAllUserIds(userIds)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void listRecentOfflineUsers(ListRecentOfflineUsersRequest request,
                                       StreamObserver<ListRecentOfflineUsersResponse> responseObserver) {
        long since = request.getSinceMs();
        long until = request.getUntilMs();
        int limit = request.getLimit();
        if (until < since) {
            log.warn("listRecentOfflineUsers invalid window: since={} until={}", since, until);
            responseObserver.onNext(ListRecentOfflineUsersResponse.getDefaultInstance());
            responseObserver.onCompleted();
            return;
        }
        int effectiveLimit = limit <= 0 ? 5_000 : Math.min(limit, 50_000);
        List<Long> userIds;
        try {
            userIds = onlineSessionRecorder.findRecentOfflineUserIds(since, until, effectiveLimit);
        } catch (Exception e) {
            log.error("listRecentOfflineUsers failed: since={} until={} limit={}",
                    since, until, effectiveLimit, e);
            userIds = List.of();
        }
        responseObserver.onNext(ListRecentOfflineUsersResponse.newBuilder()
                .addAllUserIds(userIds)
                .build());
        responseObserver.onCompleted();
    }
}
