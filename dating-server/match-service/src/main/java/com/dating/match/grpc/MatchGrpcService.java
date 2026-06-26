package com.dating.match.grpc;

import com.dating.match.config.QuotaConfig;
import com.dating.match.constant.SwipeDirection;
import com.dating.match.entity.LikeRecord;
import com.dating.match.entity.Match;
import com.dating.match.entity.VisitRecord;
import com.dating.match.exception.BizException;
import com.dating.match.exception.ErrorCodes;
import com.dating.match.grpc.interceptor.UserContext;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.VisitRecordManager;
import com.dating.match.service.FeedService;
import com.dating.match.service.QuotaService;
import com.dating.match.service.SuperHiService;
import com.dating.match.service.SwipeService;
import com.dating.match.service.dto.CardItem;
import com.dating.youjianxin.proto.match.Card;
import com.dating.youjianxin.proto.match.CardUserType;
import com.dating.youjianxin.proto.match.GetQuotaRequest;
import com.dating.youjianxin.proto.match.GetQuotaResponse;
import com.dating.youjianxin.proto.match.GetTodayFeedRequest;
import com.dating.youjianxin.proto.match.GetTodayFeedResponse;
import com.dating.youjianxin.proto.match.LikeInfo;
import com.dating.youjianxin.proto.match.LikeSource;
import com.dating.youjianxin.proto.match.ListFriendUserIdsRequest;
import com.dating.youjianxin.proto.match.ListFriendUserIdsResponse;
import com.dating.youjianxin.proto.match.ListLikesOfMeRequest;
import com.dating.youjianxin.proto.match.ListLikesOfMeResponse;
import com.dating.youjianxin.proto.match.ListMatchesRequest;
import com.dating.youjianxin.proto.match.ListMatchesResponse;
import com.dating.youjianxin.proto.match.ListVisitsOfMeRequest;
import com.dating.youjianxin.proto.match.ListVisitsOfMeResponse;
import com.dating.youjianxin.proto.match.RecordVisitRequest;
import com.dating.youjianxin.proto.match.RecordVisitResponse;
import com.dating.youjianxin.proto.match.MatchInfo;
import com.dating.youjianxin.proto.match.MatchServiceGrpc;
import com.dating.youjianxin.proto.match.SuperHiRequest;
import com.dating.youjianxin.proto.match.SuperHiResponse;
import com.dating.youjianxin.proto.match.SwipeRequest;
import com.dating.youjianxin.proto.match.SwipeResponse;
import com.dating.youjianxin.proto.match.VisitInfo;
import com.dating.youjianxin.proto.payment.SubscriptionTier;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * MatchService gRPC 适配层(5 RPC)。
 *
 * <p>caller user_id 从 metadata x-user-id 透传(见 UserContextServerInterceptor)。
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class MatchGrpcService extends MatchServiceGrpc.MatchServiceImplBase {

    /** 单 user 一次 fanout 最多写多少个好友 timeline ── 与 PostFanoutService 协同的硬上限 */
    private static final int MAX_FRIEND_USER_IDS = 1000;

    private final FeedService feedService;
    private final SwipeService swipeService;
    private final SuperHiService superHiService;
    private final MatchManager matchManager;
    private final LikeRecordManager likeRecordManager;
    private final VisitRecordManager visitRecordManager;
    private final QuotaService quotaService;

    @Override
    public void getTodayFeed(GetTodayFeedRequest req, StreamObserver<GetTodayFeedResponse> out) {
        try {
            long userId = requireCaller();
            int count = req.getCount();
            if (count > 20) {
                throw new BizException(ErrorCodes.INVALID_ARGUMENT, "count must be <= 20");
            }
            FeedService.FeedResult r = feedService.getTodayFeed(userId, count);
            GetTodayFeedResponse.Builder b = GetTodayFeedResponse.newBuilder()
                    .setExhausted(r.exhausted());
            for (CardItem c : r.cards()) {
                b.addCards(toCardProto(c));
            }
            out.onNext(b.build());
            out.onCompleted();
        } catch (BizException e) {
            out.onError(toStatus(e));
        } catch (Exception e) {
            log.error("getTodayFeed failed", e);
            out.onError(Status.INTERNAL.withDescription("getTodayFeed failed").asRuntimeException());
        }
    }

    @Override
    public void swipe(SwipeRequest req, StreamObserver<SwipeResponse> out) {
        try {
            long userId = requireCaller();
            short direction = (short) req.getDirection().getNumber();
            if (direction != SwipeDirection.LEFT && direction != SwipeDirection.RIGHT) {
                throw new BizException(ErrorCodes.INVALID_ARGUMENT, "direction must be LEFT or RIGHT");
            }
            SwipeService.SwipeResult r = swipeService.swipe(userId, req.getTargetUserId(), direction);
            out.onNext(SwipeResponse.newBuilder()
                    .setMatchId(r.matchId())
                    .build());
            out.onCompleted();
        } catch (BizException e) {
            out.onError(toStatus(e));
        } catch (Exception e) {
            log.error("swipe failed", e);
            out.onError(Status.INTERNAL.withDescription("swipe failed").asRuntimeException());
        }
    }

    @Override
    public void superHi(SuperHiRequest req, StreamObserver<SuperHiResponse> out) {
        try {
            long userId = requireCaller();
            SuperHiService.SuperHiResult r = superHiService.superHi(
                    userId, req.getTargetUserId(), req.getClientRequestId());
            out.onNext(SuperHiResponse.newBuilder()
                    .setMatchId(r.matchId())
                    .setCoinsUsed(r.coinsUsed())
                    .build());
            out.onCompleted();
        } catch (BizException e) {
            out.onError(toStatus(e));
        } catch (Exception e) {
            log.error("superHi failed", e);
            out.onError(Status.INTERNAL.withDescription("superHi failed").asRuntimeException());
        }
    }

    @Override
    public void listMatches(ListMatchesRequest req, StreamObserver<ListMatchesResponse> out) {
        try {
            long userId = requireCaller();
            int pageSize = req.getPageSize() <= 0 ? 20 : Math.min(100, req.getPageSize());
            OffsetDateTime cursor = parseCursor(req.getPageToken());
            List<Match> rows = matchManager.listByUser(userId, cursor, pageSize);
            ListMatchesResponse.Builder b = ListMatchesResponse.newBuilder();
            for (Match m : rows) {
                long partner = m.getUserIdLow().equals(userId) ? m.getUserIdHigh() : m.getUserIdLow();
                b.addMatches(MatchInfo.newBuilder()
                        .setMatchId(m.getId())
                        .setPartnerUserId(partner)
                        .setMatchedAtMs(m.getMatchedAt().toInstant().toEpochMilli())
                        .setSource(toMatchSourceProto(m.getSource()))
                        .build());
            }
            if (!rows.isEmpty()) {
                b.setNextPageToken(String.valueOf(rows.get(rows.size() - 1).getMatchedAt().toInstant().toEpochMilli()));
            }
            out.onNext(b.build());
            out.onCompleted();
        } catch (BizException e) {
            out.onError(toStatus(e));
        } catch (Exception e) {
            log.error("listMatches failed", e);
            out.onError(Status.INTERNAL.withDescription("listMatches failed").asRuntimeException());
        }
    }

    @Override
    public void getQuota(GetQuotaRequest req, StreamObserver<GetQuotaResponse> out) {
        try {
            long userId = requireCaller();
            QuotaService.QuotaSnapshot snap = quotaService.snapshot(userId);
            QuotaConfig.DailyQuota quota = snap.dailyQuota();
            QuotaService.Used u = snap.used();
            out.onNext(GetQuotaResponse.newBuilder()
                    .setTier(tierString(snap.tier()))
                    .setDailyRightSwipeLimit(quota.getRightSwipe())
                    .setDailyRightSwipeUsed(u.rightSwipe())
                    .setDailyCardLimit(quota.getCards())
                    .setDailyCardUsed(u.cards())
                    .setDailySuperHiLimit(quota.getSuperHi())
                    .setDailySuperHiUsed(u.superHi())
                    .setSuperHiCoinPrice(100)
                    .build());
            out.onCompleted();
        } catch (BizException e) {
            out.onError(toStatus(e));
        } catch (Exception e) {
            log.error("getQuota failed", e);
            out.onError(Status.INTERNAL.withDescription("getQuota failed").asRuntimeException());
        }
    }

    @Override
    public void listFriendUserIds(ListFriendUserIdsRequest req, StreamObserver<ListFriendUserIdsResponse> out) {
        try {
            long userId = req.getUserId();
            if (userId <= 0) {
                throw new BizException(ErrorCodes.INVALID_ARGUMENT, "user_id must be > 0");
            }
            List<Long> ids = matchManager.listFriendUserIds(userId, MAX_FRIEND_USER_IDS);
            out.onNext(ListFriendUserIdsResponse.newBuilder()
                    .addAllFriendUserIds(ids)
                    .build());
            out.onCompleted();
        } catch (BizException e) {
            out.onError(toStatus(e));
        } catch (Exception e) {
            log.error("listFriendUserIds failed userId={}", req.getUserId(), e);
            out.onError(Status.INTERNAL.withDescription("listFriendUserIds failed").asRuntimeException());
        }
    }

    @Override
    public void listLikesOfMe(ListLikesOfMeRequest req, StreamObserver<ListLikesOfMeResponse> out) {
        try {
            long userId = requireCaller();
            int pageSize = req.getPageSize() <= 0 ? 20 : Math.min(100, req.getPageSize());
            OffsetDateTime cursor = parseCursor(req.getPageToken());
            List<LikeRecord> rows = likeRecordManager.listToMe(userId, cursor, pageSize);
            ListLikesOfMeResponse.Builder b = ListLikesOfMeResponse.newBuilder();
            for (LikeRecord r : rows) {
                b.addLikes(LikeInfo.newBuilder()
                        .setLikeId(r.getId())
                        .setFromUserId(r.getFromUserId())
                        .setSource(toLikeSourceProto(r.getSource()))
                        .setLikedAtMs(r.getLikedAt().toInstant().toEpochMilli())
                        .build());
            }
            if (!rows.isEmpty()) {
                b.setNextPageToken(String.valueOf(rows.get(rows.size() - 1).getLikedAt().toInstant().toEpochMilli()));
            }
            out.onNext(b.build());
            out.onCompleted();
        } catch (BizException e) {
            out.onError(toStatus(e));
        } catch (Exception e) {
            log.error("listLikesOfMe failed", e);
            out.onError(Status.INTERNAL.withDescription("listLikesOfMe failed").asRuntimeException());
        }
    }

    @Override
    public void recordVisit(RecordVisitRequest req, StreamObserver<RecordVisitResponse> out) {
        long viewerUserId = 0L;
        long targetUserId = req.getTargetUserId();
        try {
            viewerUserId = requireCaller();
            // 自访问短路:不落库
            if (viewerUserId == targetUserId) {
                out.onNext(RecordVisitResponse.newBuilder().setOk(true).build());
                out.onCompleted();
                return;
            }
            if (targetUserId <= 0) {
                throw new BizException(ErrorCodes.INVALID_ARGUMENT, "target_user_id must be > 0");
            }
            visitRecordManager.recordVisit(viewerUserId, targetUserId);
        } catch (BizException e) {
            // 业务参数错(target_user_id <= 0 / missing caller)按规范上抛,App 修
            out.onError(toStatus(e));
            return;
        } catch (Exception e) {
            // DB / 其他基础设施异常 fail-open:visit 列表少一行不影响主流程
            log.warn("recordVisit failed viewerUserId={} targetUserId={};fail-open",
                    viewerUserId, targetUserId, e);
        }
        out.onNext(RecordVisitResponse.newBuilder().setOk(true).build());
        out.onCompleted();
    }

    @Override
    public void listVisitsOfMe(ListVisitsOfMeRequest req, StreamObserver<ListVisitsOfMeResponse> out) {
        try {
            long userId = requireCaller();
            int pageSize = req.getPageSize() <= 0 ? 20 : Math.min(100, req.getPageSize());
            OffsetDateTime cursor = parseCursor(req.getPageToken());
            List<VisitRecord> rows = visitRecordManager.listToMe(userId, cursor, pageSize);
            ListVisitsOfMeResponse.Builder b = ListVisitsOfMeResponse.newBuilder();
            for (VisitRecord r : rows) {
                b.addVisits(VisitInfo.newBuilder()
                        .setVisitId(r.getId())
                        .setFromUserId(r.getFromUserId())
                        .setVisitCount(r.getVisitCount() == null ? 0 : r.getVisitCount())
                        .setFirstVisitedAtMs(r.getFirstVisitedAt().toInstant().toEpochMilli())
                        .setLastVisitedAtMs(r.getLastVisitedAt().toInstant().toEpochMilli())
                        .build());
            }
            if (!rows.isEmpty()) {
                b.setNextPageToken(String.valueOf(
                        rows.get(rows.size() - 1).getLastVisitedAt().toInstant().toEpochMilli()));
            }
            out.onNext(b.build());
            out.onCompleted();
        } catch (BizException e) {
            out.onError(toStatus(e));
        } catch (Exception e) {
            log.error("listVisitsOfMe failed", e);
            out.onError(Status.INTERNAL.withDescription("listVisitsOfMe failed").asRuntimeException());
        }
    }

    // ────────────────────────── helpers ──────────────────────────

    private long requireCaller() {
        Long uid = UserContext.callerUserId();
        if (uid == null || uid <= 0) {
            throw new BizException(ErrorCodes.UNAUTHENTICATED, "missing x-user-id");
        }
        return uid;
    }

    private static Card toCardProto(CardItem c) {
        Card.Builder b = Card.newBuilder()
                .setTargetUserId(c.targetUserId())
                .setTargetUserType(c.targetUserTypeDb() == 2 ? CardUserType.CARD_USER_TYPE_DH : CardUserType.CARD_USER_TYPE_BH)
                .setNickname(c.nickname() == null ? "" : c.nickname())
                .setAge(c.age())
                .setBio(c.bio() == null ? "" : c.bio())
                .setDistanceKm(c.distanceKm())
                .setStateCode(c.stateCode() == null ? "" : c.stateCode())
                .setCity(c.city() == null ? "" : c.city());
        if (c.photoKeys() != null) b.addAllPhotoKeys(c.photoKeys());
        return b.build();
    }

    private static com.dating.youjianxin.proto.match.MatchSource toMatchSourceProto(String s) {
        if (s == null) return com.dating.youjianxin.proto.match.MatchSource.MATCH_SOURCE_UNSPECIFIED;
        return switch (s) {
            case "SWIPE_MATCH" -> com.dating.youjianxin.proto.match.MatchSource.MATCH_SOURCE_SWIPE_MATCH;
            case "SWIPE_SUPER_HI" -> com.dating.youjianxin.proto.match.MatchSource.MATCH_SOURCE_SWIPE_SUPER_HI;
            default -> com.dating.youjianxin.proto.match.MatchSource.MATCH_SOURCE_UNSPECIFIED;
        };
    }

    private static LikeSource toLikeSourceProto(String s) {
        if (s == null) return LikeSource.LIKE_SOURCE_UNSPECIFIED;
        return switch (s) {
            case "SWIPE_RIGHT" -> LikeSource.LIKE_SOURCE_SWIPE_RIGHT;
            case "SUPER_HI" -> LikeSource.LIKE_SOURCE_SUPER_HI;
            default -> LikeSource.LIKE_SOURCE_UNSPECIFIED;
        };
    }

    private static String tierString(SubscriptionTier t) {
        return switch (t) {
            case SUBSCRIPTION_TIER_WEEKLY -> "WEEKLY";
            case SUBSCRIPTION_TIER_MONTHLY -> "MONTHLY";
            case SUBSCRIPTION_TIER_YEARLY -> "YEARLY";
            default -> "FREE";
        };
    }

    private static OffsetDateTime parseCursor(String pageToken) {
        if (pageToken == null || pageToken.isBlank()) return null;
        try {
            long epochMs = Long.parseLong(pageToken);
            return OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneOffset.UTC);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static io.grpc.StatusRuntimeException toStatus(BizException e) {
        int code = e.getCode();
        Status base = switch (code) {
            case ErrorCodes.UNAUTHENTICATED -> Status.UNAUTHENTICATED;
            case ErrorCodes.INVALID_ARGUMENT, ErrorCodes.SWIPE_SELF_NOT_ALLOWED -> Status.INVALID_ARGUMENT;
            case ErrorCodes.SWIPE_TARGET_NOT_FOUND, ErrorCodes.MATCH_NOT_FOUND -> Status.NOT_FOUND;
            case ErrorCodes.CONCURRENT_SWIPE -> Status.ABORTED;
            case ErrorCodes.QUOTA_RIGHT_SWIPE_EXCEEDED, ErrorCodes.QUOTA_CARD_EXCEEDED,
                 ErrorCodes.QUOTA_SUPER_HI_EXCEEDED -> Status.RESOURCE_EXHAUSTED;
            case ErrorCodes.INSUFFICIENT_COINS -> Status.FAILED_PRECONDITION;
            default -> Status.INTERNAL;
        };
        return base.withDescription(e.getMessage()).asRuntimeException();
    }
}
