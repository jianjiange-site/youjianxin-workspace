package com.dating.match.service;

import com.dating.match.client.PaymentClient;
import com.dating.match.client.UserClient;
import com.dating.match.config.QuotaConfig;
import com.dating.match.constant.LikeSource;
import com.dating.match.constant.MatchSource;
import com.dating.match.constant.SwipeDirection;
import com.dating.match.entity.UserSwipeHistory;
import com.dating.match.exception.BizException;
import com.dating.match.exception.ErrorCodes;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.SwipeHistoryManager;
import com.dating.youjianxin.proto.user.UserProfile;
import com.dating.youjianxin.proto.user.UserType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SuperHi:订阅赠送 1 次/天 → 否则扣金币;BH/DH 一律立即匹配。
 *
 * <p>详见 docs §5.1 矩阵 + §5.2 末尾(SuperHi DH 立即不进延迟队列)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperHiService {

    @Value("${match.super-hi.coin-price:100}")
    private long coinPrice;

    private final SwipeLockTemplate swipeLockTemplate;
    private final QuotaService quotaService;
    private final SwipeHistoryManager swipeHistoryManager;
    private final LikeRecordManager likeRecordManager;
    private final MatchService matchService;
    private final UserClient userClient;
    private final PaymentClient paymentClient;

    public SuperHiResult superHi(long userId, long targetUserId, String clientRequestId) {
        if (userId == targetUserId) {
            throw new BizException(ErrorCodes.SWIPE_SELF_NOT_ALLOWED, "cannot SuperHi self");
        }
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "client_request_id required");
        }

        return swipeLockTemplate.runUnderSwipeLock(userId, targetUserId,
                () -> doSuperHi(userId, targetUserId, clientRequestId));
    }

    private SuperHiResult doSuperHi(long userId, long targetUserId, String clientRequestId) {
        // 幂等:同一 (userId, targetUserId) 之前 swipe 过(包括 SUPER_HI)→ 返上次结果
        UserSwipeHistory existing = swipeHistoryManager.findOne(userId, targetUserId);
        if (existing != null) {
            log.warn("Duplicate SuperHi: userId={} targetUserId={} direction={};idempotent",
                    userId, targetUserId, existing.getDirection());
            return new SuperHiResult(0L, 0);
        }

        QuotaService.QuotaSnapshot snap = quotaService.snapshot(userId);
        QuotaConfig.DailyQuota dailyQuota = snap.dailyQuota();

        // 扣 cards + right_swipe(SuperHi 也消耗这两项)
        quotaService.incrCardOrThrow(userId, dailyQuota);
        quotaService.incrRightSwipeOrThrow(userId, dailyQuota);

        // 优先用订阅赠送;否则扣金币
        boolean usedFreeQuota = quotaService.tryUseSuperHiQuota(userId, dailyQuota);
        int coinsUsed = 0;
        if (!usedFreeQuota) {
            String idemKey = "superhi:" + clientRequestId;
            paymentClient.consumeCoinsIdempotent(userId, coinPrice, "SUPER_HI", idemKey);
            coinsUsed = (int) coinPrice;
        }

        // 查 target user_type 决定 DH_OPENING 是否触发(MatchService 已处理这部分)
        short targetUserTypeDb = lookupTargetUserTypeDb(targetUserId);

        // 事务:写 swipeHistory + createMatch
        long matchId = atomicWrite(userId, targetUserId, targetUserTypeDb);

        return new SuperHiResult(matchId, coinsUsed);
    }

    private short lookupTargetUserTypeDb(long targetUserId) {
        try {
            List<UserProfile> profiles = userClient.batchGetProfiles(List.of(targetUserId));
            if (profiles.isEmpty()) {
                throw new BizException(ErrorCodes.SWIPE_TARGET_NOT_FOUND, "target not found");
            }
            return (profiles.get(0).getUserType() == UserType.USER_TYPE_DH) ? (short) 2 : (short) 1;
        } catch (BizException e) { throw e; }
        catch (Exception e) {
            log.warn("lookup target user_type failed for SuperHi targetUserId={}", targetUserId, e);
            return (short) 1;  // 默认 BH
        }
    }

    @Transactional
    protected long atomicWrite(long userId, long targetUserId, short targetUserTypeDb) {
        swipeHistoryManager.insert(userId, targetUserId, targetUserTypeDb, SwipeDirection.SUPER_HI);
        // 真人 SuperHi 落 like_record(归档语义);同事务,createMatch 失败回滚则一起回
        likeRecordManager.insertIfAbsent(userId, targetUserId, LikeSource.SUPER_HI);
        return matchService.createMatch(userId, targetUserId, MatchSource.SWIPE_SUPER_HI).getId();
    }

    public record SuperHiResult(long matchId, int coinsUsed) {}
}
