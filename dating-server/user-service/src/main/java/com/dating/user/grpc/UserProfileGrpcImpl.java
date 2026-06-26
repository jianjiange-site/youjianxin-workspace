package com.dating.user.grpc;

import com.dating.user.converter.ProtoMapper;
import com.dating.user.exception.BizException;
import com.dating.user.exception.ErrorCodes;
import com.dating.user.grpc.interceptor.UserContext;
import com.dating.user.service.UserAvatarService;
import com.dating.user.service.UserInterestService;
import com.dating.user.service.UserProfileService;
import com.dating.user.service.dto.PresignAvatarResult;
import com.dating.user.vo.UserProfileVO;
import com.dating.youjianxin.proto.user.BatchGetProfileRequest;
import com.dating.youjianxin.proto.user.BatchGetProfileResponse;
import com.dating.youjianxin.proto.user.ConfirmAvatarUploadRequest;
import com.dating.youjianxin.proto.user.ConfirmAvatarUploadResponse;
import com.dating.youjianxin.proto.user.GetProfileRequest;
import com.dating.youjianxin.proto.user.GetProfileResponse;
import com.dating.youjianxin.proto.user.PresignAvatarUploadRequest;
import com.dating.youjianxin.proto.user.PresignAvatarUploadResponse;
import com.dating.youjianxin.proto.user.ReplaceUserInterestsRequest;
import com.dating.youjianxin.proto.user.ReplaceUserInterestsResponse;
import com.dating.youjianxin.proto.user.UpdateProfileRequest;
import com.dating.youjianxin.proto.user.UpdateProfileResponse;
import com.dating.youjianxin.proto.user.UpsertOnboardingRequest;
import com.dating.youjianxin.proto.user.UpsertOnboardingResponse;
import com.dating.youjianxin.proto.user.UserProfileServiceGrpc;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.List;

// U7 完成接入:7 RPC 全员实装。
//   GetProfile / BatchGetProfile / UpdateProfile / UpsertOnboarding -> UserProfileService
//   ReplaceUserInterests -> UserInterestService
//   PresignAvatarUpload / ConfirmAvatarUpload -> UserAvatarService
// 写类 RPC 的 callerUserId 走 metadata x-user-id (UserContext)。
@GrpcService
@RequiredArgsConstructor
public class UserProfileGrpcImpl
        extends UserProfileServiceGrpc.UserProfileServiceImplBase {

    private final UserProfileService userProfileService;
    private final UserAvatarService userAvatarService;
    private final UserInterestService userInterestService;
    private final ProtoMapper protoMapper;

    @Override
    public void getProfile(GetProfileRequest request,
                           StreamObserver<GetProfileResponse> observer) {
        UserProfileVO vo = userProfileService.getProfile(request.getTargetUserId());
        observer.onNext(GetProfileResponse.newBuilder()
                .setProfile(protoMapper.toUserProfileProto(vo))
                .build());
        observer.onCompleted();
    }

    @Override
    public void batchGetProfile(BatchGetProfileRequest request,
                                StreamObserver<BatchGetProfileResponse> observer) {
        List<UserProfileVO> vos = userProfileService.batchGetProfile(request.getTargetUserIdsList());
        BatchGetProfileResponse.Builder b = BatchGetProfileResponse.newBuilder();
        for (UserProfileVO vo : vos) {
            b.addProfiles(protoMapper.toUserProfileProto(vo));
        }
        observer.onNext(b.build());
        observer.onCompleted();
    }

    @Override
    public void updateProfile(UpdateProfileRequest request,
                              StreamObserver<UpdateProfileResponse> observer) {
        long callerUserId = requireCaller();
        boolean ok = userProfileService.updateProfile(callerUserId, request);
        observer.onNext(UpdateProfileResponse.newBuilder().setOk(ok).build());
        observer.onCompleted();
    }

    @Override
    public void upsertOnboarding(UpsertOnboardingRequest request,
                                 StreamObserver<UpsertOnboardingResponse> observer) {
        long callerUserId = requireCaller();
        UserProfileVO vo = userProfileService.upsertOnboarding(callerUserId, request);
        observer.onNext(UpsertOnboardingResponse.newBuilder()
                .setProfile(protoMapper.toUserProfileProto(vo))
                .build());
        observer.onCompleted();
    }

    @Override
    public void replaceUserInterests(ReplaceUserInterestsRequest request,
                                     StreamObserver<ReplaceUserInterestsResponse> observer) {
        long callerUserId = requireCaller();
        int savedCount = userInterestService.replaceAll(callerUserId, request.getInterestsList());
        observer.onNext(ReplaceUserInterestsResponse.newBuilder()
                .setOk(true)
                .setSavedCount(savedCount)
                .build());
        observer.onCompleted();
    }

    @Override
    public void presignAvatarUpload(PresignAvatarUploadRequest request,
                                    StreamObserver<PresignAvatarUploadResponse> observer) {
        long callerUserId = requireCaller();
        PresignAvatarResult r = userAvatarService.presign(
                callerUserId, request.getExt(), request.getExpectedSizeBytes());
        observer.onNext(PresignAvatarUploadResponse.newBuilder()
                .setPresignedUrl(r.presignedUrl())
                .setObjectKey(r.objectKey())
                .setExpiresAtMs(r.expiresAtMs())
                .build());
        observer.onCompleted();
    }

    @Override
    public void confirmAvatarUpload(ConfirmAvatarUploadRequest request,
                                    StreamObserver<ConfirmAvatarUploadResponse> observer) {
        long callerUserId = requireCaller();
        UserProfileVO.AvatarVO avatar = userAvatarService.confirm(callerUserId, request.getObjectKey());
        observer.onNext(ConfirmAvatarUploadResponse.newBuilder()
                .setOk(true)
                .setAvatar(protoMapper.toAvatarProto(avatar))
                .build());
        observer.onCompleted();
    }

    private static long requireCaller() {
        Long caller = UserContext.callerUserId();
        if (caller == null || caller <= 0) {
            throw new BizException(ErrorCodes.UNAUTHENTICATED, "missing caller user id");
        }
        return caller;
    }
}
