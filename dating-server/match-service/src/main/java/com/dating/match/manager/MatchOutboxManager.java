package com.dating.match.manager;

import com.dating.match.constant.OutboxStatus;
import com.dating.match.entity.MatchOutbox;
import com.dating.match.mapper.MatchOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * match_outbox 包装:写入待 retry 任务 + 状态机推进。
 *
 * <p>详见 docs §5.3 / §6.6 MatchOutboxRetry。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchOutboxManager {

    private final MatchOutboxMapper mapper;

    public void enqueue(long matchId, String action, String payloadJson) {
        MatchOutbox row = new MatchOutbox();
        row.setMatchId(matchId);
        row.setAction(action);
        row.setPayloadJson(payloadJson == null ? "{}" : payloadJson);
        row.setAttempts(0);
        row.setNextRetryAt(OffsetDateTime.now());
        row.setStatus(OutboxStatus.PENDING);
        mapper.insert(row);
    }

    public List<MatchOutbox> pendingDue(int limit) {
        return mapper.selectPendingDue(limit);
    }

    public void markDone(long id) {
        mapper.markDone(id);
    }

    /**
     * 失败回写:exp backoff;>=MAX_ATTEMPTS 置 DEAD。
     * @param attemptsAfter 已重试次数(本次失败后的)
     */
    public void markFailedWithBackoff(long id, int attemptsAfter) {
        String status = attemptsAfter >= OutboxStatus.MAX_ATTEMPTS ? OutboxStatus.DEAD : OutboxStatus.PENDING;
        long delaySec = Math.min(3600L, (long) Math.pow(2, attemptsAfter));    // 1,2,4,8,...,3600s cap
        OffsetDateTime next = OffsetDateTime.now().plusSeconds(delaySec);
        mapper.updateRetry(id, attemptsAfter, next.toString(), status);
        if (OutboxStatus.DEAD.equals(status)) {
            log.error("match_outbox DEAD: id={} attempts={} ── 人工介入,见 docs §5.3", id, attemptsAfter);
        }
    }
}
