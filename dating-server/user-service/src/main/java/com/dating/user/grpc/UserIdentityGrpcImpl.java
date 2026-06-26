package com.dating.user.grpc;

import com.dating.user.converter.ProtoMapper;
import com.dating.user.service.UserBanService;
import com.dating.user.service.UserIdentityService;
import com.dating.user.service.dto.CheckBanResult;
import com.dating.user.service.dto.ResolveOrCreateResult;
import com.dating.youjianxin.proto.user.AppName;
import com.dating.youjianxin.proto.user.CheckBanRequest;
import com.dating.youjianxin.proto.user.CheckBanResponse;
import com.dating.youjianxin.proto.user.Platform;
import com.dating.youjianxin.proto.user.ResolveOrCreateByDeviceRequest;
import com.dating.youjianxin.proto.user.ResolveOrCreateByPhoneRequest;
import com.dating.youjianxin.proto.user.ResolveOrCreateByThirdPartyRequest;
import com.dating.youjianxin.proto.user.ResolveOrCreateResponse;
import com.dating.youjianxin.proto.user.ThirdPartyPlatform;
import com.dating.youjianxin.proto.user.UserIdentityServiceGrpc;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

// U5 接入 service:proto request getter → 内部参数;service 出参 → ProtoMapper.build*。
// 异常由 GrpcExceptionAdvice 统一兜底转 StatusRuntimeException。
@GrpcService
@RequiredArgsConstructor
public class UserIdentityGrpcImpl
        extends UserIdentityServiceGrpc.UserIdentityServiceImplBase {

    private final UserIdentityService userIdentityService;
    private final UserBanService userBanService;
    private final ProtoMapper protoMapper;

    @Override
    public void resolveOrCreateByPhone(ResolveOrCreateByPhoneRequest request,
                                       StreamObserver<ResolveOrCreateResponse> observer) {
        ResolveOrCreateResult r = userIdentityService.resolveByPhone(
                request.getPhoneE164(),
                appNameToShort(request.getAppName()));
        observer.onNext(protoMapper.buildResolveOrCreateResponse(r.userId(), r.pending(), r.newlyCreated()));
        observer.onCompleted();
    }

    @Override
    public void resolveOrCreateByThirdParty(ResolveOrCreateByThirdPartyRequest request,
                                            StreamObserver<ResolveOrCreateResponse> observer) {
        ResolveOrCreateResult r = userIdentityService.resolveByThirdParty(
                thirdPartyPlatformToShort(request.getPlatform()),
                request.getThirdPartyUserId(),
                appNameToShort(request.getAppName()),
                request.getGoogleEmail());
        observer.onNext(protoMapper.buildResolveOrCreateResponse(r.userId(), r.pending(), r.newlyCreated()));
        observer.onCompleted();
    }

    @Override
    public void resolveOrCreateByDevice(ResolveOrCreateByDeviceRequest request,
                                        StreamObserver<ResolveOrCreateResponse> observer) {
        ResolveOrCreateResult r = userIdentityService.resolveByDevice(
                request.getDeviceId(),
                platformToShort(request.getPlatform()),
                appNameToShort(request.getAppName()));
        observer.onNext(protoMapper.buildResolveOrCreateResponse(r.userId(), r.pending(), r.newlyCreated()));
        observer.onCompleted();
    }

    @Override
    public void checkBan(CheckBanRequest request,
                         StreamObserver<CheckBanResponse> observer) {
        CheckBanResult r = userBanService.checkBan(request.getUserId());
        observer.onNext(protoMapper.buildCheckBanResponse(r.banned(), r.reason(), r.bannedAtMs(), r.message()));
        observer.onCompleted();
    }

    // proto enum number 直接转 short;DB 取值对齐:1=iOS 2=Android 3=Web。
    private static Short platformToShort(Platform p) {
        return p == null ? 0 : (short) p.getNumber();
    }

    private static Short thirdPartyPlatformToShort(ThirdPartyPlatform p) {
        return p == null ? 0 : (short) p.getNumber();
    }

    // proto AppName(UNSPECIFIED=0/DEFAULT=1) 与 DB app_name(默认 0) 不一致,
    // 取 proto number 同值即可;UNSPECIFIED 进 DB 即 0 (= 默认 App)。
    private static Short appNameToShort(AppName a) {
        return a == null ? 0 : (short) a.getNumber();
    }
}
