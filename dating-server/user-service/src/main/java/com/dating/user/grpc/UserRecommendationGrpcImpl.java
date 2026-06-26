package com.dating.user.grpc;

import com.dating.user.constant.GenderMapping;
import com.dating.user.entity.UserInfo;
import com.dating.user.exception.BizException;
import com.dating.user.exception.ErrorCodes;
import com.dating.user.mapper.UserInfoMapper;
import com.dating.user.service.RecommendationService;
import com.dating.user.service.dto.DhCityOverridePick;
import com.dating.user.service.dto.RecommendationQuery;
import com.jianjiange.proto.user.Candidate;
import com.jianjiange.proto.user.DhCityOverride;
import com.jianjiange.proto.user.ListDhCandidatesRequest;
import com.jianjiange.proto.user.ListDhCandidatesResponse;
import com.jianjiange.proto.user.NearbyUsersRequest;
import com.jianjiange.proto.user.NearbyUsersResponse;
import com.jianjiange.proto.user.PickDhCitiesForCallerRequest;
import com.jianjiange.proto.user.PickDhCitiesForCallerResponse;
import com.jianjiange.proto.user.UserRecommendationServiceGrpc;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 召回服务 gRPC 适配层。
 *
 * <p>职责:
 * <ul>
 *   <li>proto request 入参校验 + 拆解成 {@link RecommendationQuery}</li>
 *   <li>调用 {@link RecommendationService}</li>
 *   <li>entity → proto {@link Candidate} 转换</li>
 * </ul>
 *
 * <p>详见 dating-server/docs/match-service-prd-tech.md §4.1 / §4.2 / §6.7
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class UserRecommendationGrpcImpl
        extends UserRecommendationServiceGrpc.UserRecommendationServiceImplBase {

    /** exclude_user_ids 上限,超过拒绝(防超大入参拖慢 RPC) */
    private static final int EXCLUDE_LIMIT = 5000;

    /** 召回数量硬上限 */
    private static final int RECALL_LIMIT_MAX = 240;

    /** pickDhCitiesForCaller 单批入参 DH 数上限 */
    private static final int DH_PICK_BATCH_LIMIT = 500;

    private final RecommendationService recommendationService;
    private final UserInfoMapper userInfoMapper;

    @Override
    public void listDhCandidates(ListDhCandidatesRequest req,
                                 StreamObserver<ListDhCandidatesResponse> observer) {
        try {
            validateExclude(req.getExcludeUserIdsList());
            int limit = clampLimit(req.getLimit());
            RecommendationQuery q = RecommendationQuery.builder()
                    .targetGender(GenderMapping.toDb(req.getTargetGender()))
                    .ageMin(req.getAgeMin())
                    .ageMax(req.getAgeMax())
                    .beautyMin(req.getBeautyMin())
                    .beautyMax(req.getBeautyMax())
                    .races(req.getRacesList())
                    .excludeUserIds(req.getExcludeUserIdsList())
                    .limit(limit)
                    .build();
            List<UserInfo> rows = recommendationService.listDhCandidates(q);
            ListDhCandidatesResponse.Builder b = ListDhCandidatesResponse.newBuilder();
            for (UserInfo u : rows) {
                b.addCandidates(toCandidate(u, /* distanceKm */ -1d));
            }
            observer.onNext(b.build());
            observer.onCompleted();
        } catch (BizException e) {
            observer.onError(io.grpc.Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            log.error("listDhCandidates failed", e);
            observer.onError(io.grpc.Status.INTERNAL.withDescription("listDhCandidates failed").asRuntimeException());
        }
    }

    @Override
    public void nearbyUsers(NearbyUsersRequest req,
                            StreamObserver<NearbyUsersResponse> observer) {
        try {
            validateExclude(req.getExcludeUserIdsList());
            long callerUserId = req.getCallerUserId();
            if (callerUserId <= 0) {
                throw new BizException(ErrorCodes.INVALID_ARGUMENT, "caller_user_id required");
            }
            // Phase 1: 查 caller 自身资料,同城基准用 city_id,target gender 由 caller.gender 反推(异性恋假设)
            CallerProfile self = lookupCallerProfile(callerUserId);
            if (self == null || self.cityId == null || self.cityId <= 0) {
                // caller 没设居住地 → Phase 1 同城策略下返空(由 match-service merge 阶段 DH 补齐)
                observer.onNext(NearbyUsersResponse.getDefaultInstance());
                observer.onCompleted();
                return;
            }
            Short targetGenderDb = oppositeGender(self.gender);
            int limit = clampLimit(req.getLimit());
            RecommendationQuery q = RecommendationQuery.builder()
                    .targetGender(targetGenderDb)
                    .ageMin(req.getAgeMin())
                    .ageMax(req.getAgeMax())
                    .beautyMin(req.getBeautyMin())
                    .beautyMax(req.getBeautyMax())
                    .races(req.getRacesList())
                    .cityId(self.cityId)
                    .lastActiveWithinDays(req.getLastActiveWithinDays())
                    .excludeUserIds(req.getExcludeUserIdsList())
                    .limit(limit)
                    .build();
            List<UserInfo> rows = recommendationService.nearbyUsers(callerUserId, q);
            NearbyUsersResponse.Builder b = NearbyUsersResponse.newBuilder();
            // Phase 1 同城:distance_km 统一填 0(同城无法精确;Phase 2 Redis GEO 升级时再算)
            for (UserInfo u : rows) {
                b.addCandidates(toCandidate(u, /* distanceKm */ 0d));
            }
            observer.onNext(b.build());
            observer.onCompleted();
        } catch (BizException e) {
            observer.onError(io.grpc.Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            log.error("nearbyUsers failed", e);
            observer.onError(io.grpc.Status.INTERNAL.withDescription("nearbyUsers failed").asRuntimeException());
        }
    }

    @Override
    public void pickDhCitiesForCaller(PickDhCitiesForCallerRequest req,
                                     StreamObserver<PickDhCitiesForCallerResponse> observer) {
        try {
            long callerUserId = req.getCallerUserId();
            if (callerUserId <= 0) {
                throw new BizException(ErrorCodes.INVALID_ARGUMENT, "caller_user_id required");
            }
            List<Long> dhIds = req.getDhUserIdsList();
            if (dhIds.size() > DH_PICK_BATCH_LIMIT) {
                throw new BizException(ErrorCodes.INVALID_ARGUMENT,
                        "dh_user_ids size exceeds " + DH_PICK_BATCH_LIMIT);
            }
            Map<Long, DhCityOverridePick> picks =
                    recommendationService.pickDhCitiesForCaller(callerUserId, dhIds);
            PickDhCitiesForCallerResponse.Builder b = PickDhCitiesForCallerResponse.newBuilder();
            picks.forEach((dhId, p) -> b.putOverrides(dhId,
                    DhCityOverride.newBuilder()
                            .setStateCode(p.stateCode())
                            .setCity(p.city())
                            .build()));
            observer.onNext(b.build());
            observer.onCompleted();
        } catch (BizException e) {
            observer.onError(io.grpc.Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            log.error("pickDhCitiesForCaller failed callerUserId={}", req.getCallerUserId(), e);
            observer.onError(io.grpc.Status.INTERNAL.withDescription("pickDhCitiesForCaller failed").asRuntimeException());
        }
    }

    // ────────────────────────── helpers ──────────────────────────

    private void validateExclude(List<Long> exclude) {
        if (exclude != null && exclude.size() > EXCLUDE_LIMIT) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT,
                    "exclude_user_ids size exceeds limit " + EXCLUDE_LIMIT);
        }
    }

    private int clampLimit(int limit) {
        if (limit <= 0) return RECALL_LIMIT_MAX;
        return Math.min(limit, RECALL_LIMIT_MAX);
    }

    /** 查 caller 自身资料,取 city_id 和 gender(同城基准 + 异性恋反推 target_gender) */
    private CallerProfile lookupCallerProfile(long callerUserId) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserInfo> w =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        w.eq(UserInfo::getUserId, callerUserId);
        w.select(UserInfo::getCityId, UserInfo::getGender);
        w.last("LIMIT 1");
        UserInfo u = userInfoMapper.selectOne(w);
        if (u == null) return null;
        return new CallerProfile(u.getCityId(), u.getGender());
    }

    /** 异性恋假设反推:1=MALE → 2=FEMALE / 2=FEMALE → 1=MALE / 其他 → 0(service 层不过滤性别) */
    private static Short oppositeGender(Short db) {
        if (db == null) return 0;
        return switch (db) {
            case 1 -> (short) 2;
            case 2 -> (short) 1;
            default -> 0;
        };
    }

    private record CallerProfile(Long cityId, Short gender) {}

    /**
     * UserInfo entity → Candidate proto。
     * photo_keys / bio 暂返回空 / entity 原值:
     * - bio 直接读 entity(简单标量)
     * - photo_keys 解析 custom_avatar JSONB 偏重,Phase 1 留空,
     *   match-service 端拿到 user_id 再调 BatchGetProfile 拿全 avatar
     */
    private Candidate toCandidate(UserInfo u, double distanceKm) {
        Candidate.Builder b = Candidate.newBuilder()
                .setUserId(u.getUserId() == null ? 0L : u.getUserId())
                .setNickname(nullToEmpty(u.getNickname()))
                .setAge(u.getAge() == null ? 0 : u.getAge())
                .setGender(GenderMapping.toProto(u.getGender()))
                .setUserType(com.dating.user.constant.UserTypeMapping.toProto(u.getUserType()))
                .setBeautyScore(u.getBeautyScore() == null ? 0 : u.getBeautyScore())
                .setRace(nullToEmpty(u.getRace()))
                .setCreatedAtMs(toEpochMs(u.getCreatedAt()))
                .setLastOpenAtMs(toEpochMs(u.getLastOpenAt()))
                .setCityId(u.getCityId() == null ? 0L : u.getCityId())
                .setStateCode(nullToEmpty(u.getStateCode()))
                .setCity(nullToEmpty(u.getCity()))
                .setLat(u.getLat() == null ? 0d : u.getLat())
                .setLng(u.getLng() == null ? 0d : u.getLng())
                .setDistanceKm(distanceKm)
                .setBio(nullToEmpty(u.getBio()));
        // photo_keys 留空,由 match-service 后续 batchGetProfile 拿
        b.addAllPhotoKeys(Collections.emptyList());
        return b.build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static long toEpochMs(OffsetDateTime t) {
        return t == null ? 0L : t.toInstant().toEpochMilli();
    }
}
