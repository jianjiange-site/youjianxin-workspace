package com.dating.mobilegateway.client;

import com.google.protobuf.ByteString;
import com.dating.youjianxin.proto.im.GenerateCallTokenRequest;
import com.dating.youjianxin.proto.im.GenerateCallTokenResponse;
import com.dating.youjianxin.proto.im.GetImTokenRequest;
import com.dating.youjianxin.proto.im.GetImTokenResponse;
import com.dating.youjianxin.proto.im.ImServiceGrpc;
import com.dating.youjianxin.proto.im.OnRawCallbackResponse;
import com.dating.youjianxin.proto.im.RawCallback;
import com.dating.youjianxin.proto.im.RegisterImUserRequest;
import com.dating.youjianxin.proto.im.RegisterImUserResponse;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ImClient {

    // 登录关键路径上同步调,设 deadline 防 im-service 慢拖垮登录(超时由调用方 catch 转告警)。
    private static final long REGISTER_DEADLINE_SECONDS = 3;

    @GrpcClient("im-service")
    private ImServiceGrpc.ImServiceBlockingStub imStub;

    public OnRawCallbackResponse onRawCallback(String provider, byte[] payload) {
        return imStub.onRawCallback(RawCallback.newBuilder()
                .setProvider(provider)
                .setPayload(ByteString.copyFrom(payload))
                .build());
    }

    public GetImTokenResponse getImToken(String userId, int platform) {
        return imStub.getImToken(GetImTokenRequest.newBuilder()
                .setUserId(userId)
                .setPlatform(platform)
                .build());
    }

    public RegisterImUserResponse registerImUser(String userId, String nickname, String avatarUrl) {
        return imStub.withDeadlineAfter(REGISTER_DEADLINE_SECONDS, TimeUnit.SECONDS)
                .registerImUser(RegisterImUserRequest.newBuilder()
                        .setUserId(userId)
                        .setNickname(nickname)
                        .setAvatarUrl(avatarUrl != null ? avatarUrl : "")
                        .build());
    }

    public GenerateCallTokenResponse generateCallToken(String userId, String peerId) {
        return imStub.generateCallToken(GenerateCallTokenRequest.newBuilder()
                .setUserId(userId)
                .setPeerId(peerId)
                .build());
    }
}
