package com.dating.mobilegateway.service.impl;

import com.dating.mobilegateway.client.MatchClient;
import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.service.MatchService;
import com.dating.mobilegateway.vo.MatchCardVO;
import com.dating.mobilegateway.vo.MatchFeedVO;
import com.dating.mobilegateway.vo.MatchInfoVO;
import com.dating.mobilegateway.vo.MatchListVO;
import com.dating.mobilegateway.vo.MatchQuotaVO;
import com.dating.mobilegateway.vo.SuperHiResultVO;
import com.dating.mobilegateway.vo.SwipeResultVO;
import com.dating.mobilegateway.vo.VisitInfoVO;
import com.dating.mobilegateway.vo.VisitListVO;
import com.dating.youjianxin.proto.match.Card;
import com.dating.youjianxin.proto.match.CardUserType;
import com.dating.youjianxin.proto.match.GetQuotaResponse;
import com.dating.youjianxin.proto.match.GetTodayFeedResponse;
import com.dating.youjianxin.proto.match.ListMatchesResponse;
import com.dating.youjianxin.proto.match.ListVisitsOfMeResponse;
import com.dating.youjianxin.proto.match.MatchInfo;
import com.dating.youjianxin.proto.match.MatchSource;
import com.dating.youjianxin.proto.match.SuperHiResponse;
import com.dating.youjianxin.proto.match.SwipeDirection;
import com.dating.youjianxin.proto.match.SwipeResponse;
import com.dating.youjianxin.proto.match.VisitInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Match BFF 实现:proto → VO 转换 + 入参校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchServiceImpl implements MatchService {

    private final MatchClient matchClient;

    @Override
    public MatchFeedVO getFeed(int count) {
        if (count <= 0) count = 5;
        if (count > 20) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "count must be <= 20");
        }
        GetTodayFeedResponse resp = matchClient.getTodayFeed(count);
        List<MatchCardVO> cards = new ArrayList<>(resp.getCardsCount());
        for (Card c : resp.getCardsList()) {
            cards.add(toCardVO(c));
        }
        return new MatchFeedVO(cards, resp.getExhausted());
    }

    @Override
    public SwipeResultVO swipe(long targetUserId, String direction) {
        SwipeDirection dir = parseDirection(direction);
        SwipeResponse resp = matchClient.swipe(targetUserId, dir);
        return new SwipeResultVO(resp.getMatchId() == 0 ? null : resp.getMatchId());
    }

    @Override
    public SuperHiResultVO superHi(long targetUserId, String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "client_request_id required");
        }
        SuperHiResponse resp = matchClient.superHi(targetUserId, clientRequestId);
        return new SuperHiResultVO(resp.getMatchId() == 0 ? null : resp.getMatchId(),
                resp.getCoinsUsed());
    }

    @Override
    public MatchListVO listMatches(int pageSize, String pageToken) {
        if (pageSize <= 0) pageSize = 20;
        if (pageSize > 100) pageSize = 100;
        ListMatchesResponse resp = matchClient.listMatches(pageSize, pageToken);
        List<MatchInfoVO> matches = new ArrayList<>(resp.getMatchesCount());
        for (MatchInfo m : resp.getMatchesList()) {
            matches.add(new MatchInfoVO(
                    m.getMatchId(),
                    m.getPartnerUserId(),
                    m.getPartnerNickname(),
                    new ArrayList<>(m.getPartnerPhotoKeysList()),
                    m.getMatchedAtMs(),
                    sourceString(m.getSource())));
        }
        String next = resp.getNextPageToken().isEmpty() ? null : resp.getNextPageToken();
        return new MatchListVO(matches, next);
    }

    @Override
    public MatchQuotaVO getQuota() {
        GetQuotaResponse resp = matchClient.getQuota();
        return new MatchQuotaVO(
                resp.getTier(),
                resp.getDailyRightSwipeLimit(),
                resp.getDailyRightSwipeUsed(),
                resp.getDailyCardLimit(),
                resp.getDailyCardUsed(),
                resp.getDailySuperHiLimit(),
                resp.getDailySuperHiUsed(),
                resp.getSuperHiCoinPrice());
    }

    @Override
    public void recordVisit(long targetUserId) {
        if (targetUserId <= 0) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "targetUserId required");
        }
        // 服务端 fail-open,这里不读返回值(ok 一定为 true)
        matchClient.recordVisit(targetUserId);
    }

    @Override
    public VisitListVO listVisitsOfMe(int pageSize, String pageToken) {
        if (pageSize <= 0) pageSize = 20;
        if (pageSize > 100) pageSize = 100;
        ListVisitsOfMeResponse resp = matchClient.listVisitsOfMe(pageSize, pageToken);
        List<VisitInfoVO> visits = new ArrayList<>(resp.getVisitsCount());
        for (VisitInfo v : resp.getVisitsList()) {
            visits.add(new VisitInfoVO(
                    v.getVisitId(),
                    v.getFromUserId(),
                    v.getVisitCount(),
                    v.getFirstVisitedAtMs(),
                    v.getLastVisitedAtMs()));
        }
        String next = resp.getNextPageToken().isEmpty() ? null : resp.getNextPageToken();
        return new VisitListVO(visits, next);
    }

    // ────────────────────────── converters ──────────────────────────

    private static MatchCardVO toCardVO(Card c) {
        return new MatchCardVO(c.getTargetUserId(),
                c.getTargetUserType() == CardUserType.CARD_USER_TYPE_DH ? 2 : 1,
                c.getNickname(),
                c.getAge(),
                new ArrayList<>(c.getPhotoKeysList()),
                c.getBio(),
                c.getDistanceKm(),
                c.getStateCode(),
                c.getCity());
    }

    private static SwipeDirection parseDirection(String s) {
        if (s == null) throw new BizException(ErrorCodes.INVALID_ARGUMENT, "direction required");
        return switch (s.trim().toUpperCase()) {
            case "LEFT" -> SwipeDirection.SWIPE_DIRECTION_LEFT;
            case "RIGHT" -> SwipeDirection.SWIPE_DIRECTION_RIGHT;
            default -> throw new BizException(ErrorCodes.INVALID_ARGUMENT, "direction must be LEFT or RIGHT");
        };
    }

    private static String sourceString(MatchSource s) {
        return switch (s) {
            case MATCH_SOURCE_SWIPE_MATCH -> "SWIPE_MATCH";
            case MATCH_SOURCE_SWIPE_SUPER_HI -> "SWIPE_SUPER_HI";
            default -> "UNKNOWN";
        };
    }
}
