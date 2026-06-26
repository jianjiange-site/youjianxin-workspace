package com.dating.match.service;

import com.dating.match.client.UserClient;
import com.dating.match.constant.LikeSource;
import com.dating.match.constant.MatchSource;
import com.dating.match.constant.SwipeDirection;
import com.dating.match.entity.UserSwipeHistory;
import com.dating.match.exception.BizException;
import com.dating.match.exception.ErrorCodes;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.SwipeHistoryManager;
import com.dating.match.service.QuotaService.QuotaSnapshot;
import com.dating.youjianxin.proto.user.UserProfile;
import com.dating.youjianxin.proto.user.UserType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Swipe 业务编排:LEFT / RIGHT 处理。
 *
 * <p>SuperHi 走独立 RPC,不进本服务(见 SuperHiService)。
 *
 * <p>详见 docs §5.1 矩阵 / §6.6 并发问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwipeService {

    private final SwipeLockTemplate swipeLockTemplate;
    private final QuotaService quotaService;
    private final SwipeHistoryManager swipeHistoryManager;
    private final LikeRecordManager likeRecordManager;
    private final MatchService matchService;
    private final DhDelayedMatchService dhDelayedMatchService;
    private final UserClient userClient;

    public SwipeResult swipe(long userId, long targetUserId, short direction) {
        if (userId == targetUserId) {
            throw new BizException(ErrorCodes.SWIPE_SELF_NOT_ALLOWED, "cannot swipe self");
        }
        if (direction != SwipeDirection.LEFT && direction != SwipeDirection.RIGHT) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "direction must be LEFT(1) or RIGHT(2)");
        }

        return swipeLockTemplate.runUnderSwipeLock(userId, targetUserId,
                () -> doSwipe(userId, targetUserId, direction));
    }

    /** 锁内主流程;事务在 atomicWrite 里。 */
    private SwipeResult doSwipe(long userId, long targetUserId, short direction) {
        // 幂等
        UserSwipeHistory existing = swipeHistoryManager.findOne(userId, targetUserId);
        if (existing != null) {
            log.warn("Duplicate swipe (network retry?): userId={} targetUserId={} direction={};return last result",
                    userId, targetUserId, existing.getDirection());
            return idempotentResult();
        }

        // 配额限制
        QuotaSnapshot snap = quotaService.snapshot(userId);
        // 卡片配额(LEFT/RIGHT 都消耗)
        quotaService.incrCardOrThrow(userId, snap.dailyQuota());
        if (direction == SwipeDirection.RIGHT) {
            quotaService.incrRightSwipeOrThrow(userId, snap.dailyQuota());
        }

        // 查 target user_type(决定 BH 互划立即 / DH 延迟)
        UserType targetType = UserType.USER_TYPE_BH;
        try {
            List<UserProfile> profiles = userClient.batchGetProfiles(List.of(targetUserId));
            if (profiles.isEmpty()) {
                throw new BizException(ErrorCodes.SWIPE_TARGET_NOT_FOUND, "target not found");
            }
            targetType = profiles.get(0).getUserType();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("batchGetProfiles failed for swipe target, default BH targetUserId={}", targetUserId, e);
        }
        // 事务写 swipeHistory + 可能的 immediate match
        ImmediateOutcome out = atomicWrite(userId, targetUserId, direction, targetType);

        // 事务后 schedule DH 延迟
        if (out.shouldScheduleDhDelay) {
            dhDelayedMatchService.scheduleDelayedMatch(userId, targetUserId);
        }

        return new SwipeResult(out.matchId);
    }

    @Transactional
    protected ImmediateOutcome atomicWrite(long userId, long targetUserId, short direction, UserType targetType) {
        short targetUserTypeDb = (targetType == UserType.USER_TYPE_DH) ? (short) 2 : (short) 1;
        swipeHistoryManager.insert(userId, targetUserId, targetUserTypeDb, direction);

        ImmediateOutcome out = new ImmediateOutcome();
        if (direction != SwipeDirection.RIGHT) return out;

        // 真人 RIGHT_SWIPE 落 like_record(归档语义,不管后续是否互划 match);同事务,createMatch 回滚也回滚 like
        likeRecordManager.insertIfAbsent(userId, targetUserId, LikeSource.SWIPE_RIGHT);

        if (targetType == UserType.USER_TYPE_DH) {
            // DH 走延迟,事务后调度
            out.shouldScheduleDhDelay = true;
            return out;
        }
        // BH:检查对方是否曾经右划过我
        boolean mutual = swipeHistoryManager.targetHasRightSwipedCaller(userId, targetUserId);
        if (mutual) {
            out.matchId = matchService.createMatch(userId, targetUserId, MatchSource.SWIPE_MATCH).getId();
        }
        return out;
    }

    private SwipeResult idempotentResult() {
        return new SwipeResult(0L);
    }

    static class ImmediateOutcome {
        long matchId;
        boolean shouldScheduleDhDelay;
    }

    public record SwipeResult(long matchId) {}
}
