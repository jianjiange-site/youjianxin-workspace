package com.dating.mobilegateway.client;

import com.dating.mobilegateway.converter.ProtoReqBuilder;
import com.dating.mobilegateway.vo.CheckBanVO;
import com.dating.mobilegateway.vo.ResolveOrCreateVO;
import com.dating.youjianxin.proto.user.AppName;
import com.dating.youjianxin.proto.user.CheckBanResponse;
import com.dating.youjianxin.proto.user.Platform;
import com.dating.youjianxin.proto.user.ResolveOrCreateResponse;
import com.dating.youjianxin.proto.user.ThirdPartyPlatform;
import com.dating.youjianxin.proto.user.UserIdentityServiceGrpc;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

// user-service UserIdentityService 客户端封装:
//   - net.devh @GrpcClient 注入 BlockingStub,channel 走 discovery:///user-service (Nacos)
//   - 每调一次 stub.withDeadlineAfter(3s) 防长尾
//   - StatusRuntimeException 统一映射到 BizException
@Slf4j
@Component
public class UserIdentityClient {

    private static final long CALL_TIMEOUT_MS = 3000L;

    @GrpcClient("user-service")
    private UserIdentityServiceGrpc.UserIdentityServiceBlockingStub stub;

    public ResolveOrCreateVO resolveByPhone(String phoneE164, AppName appName) {
        try {
            ResolveOrCreateResponse resp = stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .resolveOrCreateByPhone(ProtoReqBuilder.resolveByPhone(phoneE164, appName));
            return new ResolveOrCreateVO(resp.getUserId(), resp.getPending(), resp.getNewlyCreated());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public ResolveOrCreateVO resolveByThirdParty(
            ThirdPartyPlatform platform, String thirdPartyUserId, AppName appName, String googleEmail) {
        try {
            ResolveOrCreateResponse resp = stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .resolveOrCreateByThirdParty(ProtoReqBuilder.resolveByThirdParty(
                            platform, thirdPartyUserId, appName, googleEmail));
            return new ResolveOrCreateVO(resp.getUserId(), resp.getPending(), resp.getNewlyCreated());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public ResolveOrCreateVO resolveByDevice(String deviceId, Platform platform, AppName appName) {
        try {
            ResolveOrCreateResponse resp = stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .resolveOrCreateByDevice(ProtoReqBuilder.resolveByDevice(deviceId, platform, appName));
            return new ResolveOrCreateVO(resp.getUserId(), resp.getPending(), resp.getNewlyCreated());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public CheckBanVO checkBan(long userId) {
        try {
            CheckBanResponse resp = stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .checkBan(ProtoReqBuilder.checkBan(userId));
            return new CheckBanVO(resp.getBanned(), resp.getReasonValue(), resp.getBannedAtMs(), resp.getMessage());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }
}
