package com.dating.match.client;

import com.dating.match.exception.BizException;
import com.dating.match.exception.ErrorCodes;
import com.dating.youjianxin.proto.user.BatchGetProfileRequest;
import com.dating.youjianxin.proto.user.BatchGetProfileResponse;
import com.dating.youjianxin.proto.user.Candidate;
import com.dating.youjianxin.proto.user.DhCityOverride;
import com.dating.youjianxin.proto.user.Gender;
import com.dating.youjianxin.proto.user.ListDhCandidatesRequest;
import com.dating.youjianxin.proto.user.ListDhCandidatesResponse;
import com.dating.youjianxin.proto.user.NearbyUsersRequest;
import com.dating.youjianxin.proto.user.NearbyUsersResponse;
import com.dating.youjianxin.proto.user.PickDhCitiesForCallerRequest;
import com.dating.youjianxin.proto.user.PickDhCitiesForCallerResponse;
import com.dating.youjianxin.proto.user.UserProfile;
import com.dating.youjianxin.proto.user.UserProfileServiceGrpc;
import com.dating.youjianxin.proto.user.UserRecommendationServiceGrpc;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * user-service 客户端封装(BatchGetProfile + UserRecommendationService 两块)。
 *
 * <p>详见 dating-server/docs/match-service-prd-tech.md §6.7。
 */
@Slf4j
@Component
public class UserClient {

    private static final long CALL_TIMEOUT_MS = 2000L;
    private static final int BATCH_GET_MAX = 200;

    @GrpcClient("user-service")
    private UserProfileServiceGrpc.UserProfileServiceBlockingStub profileStub;

    @GrpcClient("user-service")
    private UserRecommendationServiceGrpc.UserRecommendationServiceBlockingStub recoStub;

    public List<UserProfile> batchGetProfiles(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyList();
        if (userIds.size() > BATCH_GET_MAX) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT,
                    "batchGetProfile size exceeds " + BATCH_GET_MAX);
        }
        try {
            BatchGetProfileResponse resp = profileStub
                    .withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .batchGetProfile(BatchGetProfileRequest.newBuilder()
                            .addAllTargetUserIds(userIds)
                            .build());
            return resp.getProfilesList();
        } catch (StatusRuntimeException sre) {
            log.error("user batchGetProfile failed userIds={} status={}", userIds, sre.getStatus(), sre);
            throw sre;
        }
    }

    public List<Candidate> listDhCandidates(Gender targetGender,
                                            int ageMin, int ageMax,
                                            int beautyMin, int beautyMax,
                                            List<String> races,
                                            List<Long> excludeUserIds,
                                            int limit) {
        try {
            ListDhCandidatesRequest.Builder req = ListDhCandidatesRequest.newBuilder()
                    .setTargetGender(targetGender)
                    .setAgeMin(ageMin).setAgeMax(ageMax)
                    .setBeautyMin(beautyMin).setBeautyMax(beautyMax)
                    .setLimit(limit);
            if (races != null) req.addAllRaces(races);
            if (excludeUserIds != null) req.addAllExcludeUserIds(excludeUserIds);
            ListDhCandidatesResponse resp = recoStub
                    .withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .listDhCandidates(req.build());
            return resp.getCandidatesList();
        } catch (StatusRuntimeException sre) {
            log.error("listDhCandidates failed status={}", sre.getStatus(), sre);
            return Collections.emptyList();
        }
    }

    public List<Candidate> nearbyUsers(long callerUserId,
                                       int ageMin, int ageMax,
                                       int beautyMin, int beautyMax,
                                       List<String> races,
                                       int lastActiveWithinDays,
                                       List<Long> excludeUserIds,
                                       int limit) {
        try {
            NearbyUsersRequest.Builder req = NearbyUsersRequest.newBuilder()
                    .setCallerUserId(callerUserId)
                    .setAgeMin(ageMin).setAgeMax(ageMax)
                    .setBeautyMin(beautyMin).setBeautyMax(beautyMax)
                    .setLastActiveWithinDays(lastActiveWithinDays)
                    .setLimit(limit);
            if (races != null) req.addAllRaces(races);
            if (excludeUserIds != null) req.addAllExcludeUserIds(excludeUserIds);
            NearbyUsersResponse resp = recoStub
                    .withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .nearbyUsers(req.build());
            return resp.getCandidatesList();
        } catch (StatusRuntimeException sre) {
            log.error("nearbyUsers failed callerUserId={} status={}", callerUserId, sre.getStatus(), sre);
            return Collections.emptyList();
        }
    }

    /**
     * 为指定 caller 的 DH 卡片生成"同 state 不同 city"的位置覆盖值。
     *
     * <p>失败 fail-open(返回空 map),由 FeedService 走 DH 不下发位置的降级路径,
     * 不影响主 feed 流程。
     */
    public Map<Long, DhCityOverride> pickDhCitiesForCaller(long callerUserId, List<Long> dhUserIds) {
        if (dhUserIds == null || dhUserIds.isEmpty()) return Collections.emptyMap();
        try {
            PickDhCitiesForCallerResponse resp = recoStub
                    .withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .pickDhCitiesForCaller(PickDhCitiesForCallerRequest.newBuilder()
                            .setCallerUserId(callerUserId)
                            .addAllDhUserIds(dhUserIds)
                            .build());
            return resp.getOverridesMap();
        } catch (StatusRuntimeException sre) {
            log.warn("pickDhCitiesForCaller failed callerUserId={} dhCount={} status={};DH cards 无位置降级",
                    callerUserId, dhUserIds.size(), sre.getStatus());
            return Collections.emptyMap();
        }
    }
}
