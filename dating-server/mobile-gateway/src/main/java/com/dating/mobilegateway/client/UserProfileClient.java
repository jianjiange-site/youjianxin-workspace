package com.dating.mobilegateway.client;

import com.dating.mobilegateway.converter.ProtoReqBuilder;
import com.dating.mobilegateway.converter.UserProfileConverter;
import com.dating.mobilegateway.vo.AvatarVO;
import com.dating.mobilegateway.vo.PresignAvatarUploadVO;
import com.dating.mobilegateway.vo.UserInterestInputVO;
import com.dating.mobilegateway.vo.UserProfileVO;
import com.dating.youjianxin.proto.user.BatchGetProfileResponse;
import com.dating.youjianxin.proto.user.ConfirmAvatarUploadResponse;
import com.dating.youjianxin.proto.user.Gender;
import com.dating.youjianxin.proto.user.GetProfileResponse;
import com.dating.youjianxin.proto.user.PresignAvatarUploadResponse;
import com.dating.youjianxin.proto.user.ReplaceUserInterestsResponse;
import com.dating.youjianxin.proto.user.UpdateProfileResponse;
import com.dating.youjianxin.proto.user.UpsertOnboardingResponse;
import com.dating.youjianxin.proto.user.UserProfileServiceGrpc;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

// user-service UserProfileService 客户端封装。
//   - 7 个 RPC:GetProfile / BatchGetProfile / UpdateProfile / UpsertOnboarding /
//     ReplaceUserInterests / PresignAvatarUpload / ConfirmAvatarUpload
//   - caller userId 由 GrpcClientMetadataInterceptor 从 RequestContextHolder 注入 x-user-id metadata
//   - proto → VO 走 UserProfileConverter (MapStruct);VO → proto 走 ProtoReqBuilder (手写)
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileClient {

    private static final long CALL_TIMEOUT_MS = 3000L;

    private final UserProfileConverter converter;

    @GrpcClient("user-service")
    private UserProfileServiceGrpc.UserProfileServiceBlockingStub stub;

    public UserProfileVO getProfile(long targetUserId) {
        try {
            GetProfileResponse resp = stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getProfile(ProtoReqBuilder.getProfile(targetUserId));
            return resp.hasProfile() ? converter.toVO(resp.getProfile()) : null;
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public List<UserProfileVO> batchGetProfile(List<Long> targetUserIds) {
        try {
            BatchGetProfileResponse resp = stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .batchGetProfile(ProtoReqBuilder.batchGetProfile(targetUserIds));
            return converter.toVOs(resp.getProfilesList());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public boolean updateProfile(String nickname, Integer age, Integer height, String bio,
                                 String occupation, String education, String location) {
        try {
            UpdateProfileResponse resp = stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .updateProfile(ProtoReqBuilder.updateProfile(
                            nickname, age, height, bio, occupation, education, location));
            return resp.getOk();
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public UserProfileVO upsertOnboarding(
            String nickname, Gender gender, String birthday,
            Integer age, Integer height, String bio,
            String occupation, String education, String location,
            String defaultAvatarObjectKey) {
        try {
            UpsertOnboardingResponse resp = stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .upsertOnboarding(ProtoReqBuilder.upsertOnboarding(
                            nickname, gender, birthday, age, height, bio,
                            occupation, education, location, defaultAvatarObjectKey));
            return resp.hasProfile() ? converter.toVO(resp.getProfile()) : null;
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public int replaceUserInterests(List<UserInterestInputVO> interests) {
        try {
            ReplaceUserInterestsResponse resp = stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .replaceUserInterests(ProtoReqBuilder.replaceUserInterests(interests));
            return resp.getSavedCount();
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public PresignAvatarUploadVO presignAvatarUpload(String ext, long expectedSizeBytes) {
        try {
            PresignAvatarUploadResponse resp = stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .presignAvatarUpload(ProtoReqBuilder.presignAvatar(ext, expectedSizeBytes));
            return new PresignAvatarUploadVO(resp.getPresignedUrl(), resp.getObjectKey(), resp.getExpiresAtMs());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public AvatarVO confirmAvatarUpload(String objectKey) {
        try {
            ConfirmAvatarUploadResponse resp = stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .confirmAvatarUpload(ProtoReqBuilder.confirmAvatar(objectKey));
            return resp.hasAvatar() ? converter.toAvatarVO(resp.getAvatar()) : null;
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }
}
