package com.dating.match.scheduler;

import com.dating.match.client.ImClient;
import com.dating.match.constant.OutboxAction;
import com.dating.match.entity.MatchOutbox;
import com.dating.match.manager.MatchOutboxManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Match 副作用 outbox retry worker。
 *
 * <p>fixedDelay = 30s + ShedLock 多实例互斥;每次最多扫 200 条。
 * 失败 attempts++ + exp backoff;超过 MAX_ATTEMPTS 置 DEAD 并打 ERROR(docs §5.3)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchOutboxRetry {

    private static final int BATCH_LIMIT = 200;
    private static final ObjectMapper M = new ObjectMapper();

    private final MatchOutboxManager outboxManager;
    private final ImClient imClient;

    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "match-outbox-retry", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void run() {
        List<MatchOutbox> due = outboxManager.pendingDue(BATCH_LIMIT);
        if (due.isEmpty()) return;
        for (MatchOutbox row : due) {
            try {
                boolean ok = dispatch(row);
                if (ok) {
                    outboxManager.markDone(row.getId());
                } else {
                    outboxManager.markFailedWithBackoff(row.getId(), row.getAttempts() + 1);
                }
            } catch (Exception e) {
                log.warn("outbox dispatch failed id={} action={} attempts={}",
                        row.getId(), row.getAction(), row.getAttempts(), e);
                outboxManager.markFailedWithBackoff(row.getId(), row.getAttempts() + 1);
            }
        }
    }

    private boolean dispatch(MatchOutbox row) throws Exception {
        JsonNode payload = M.readTree(row.getPayloadJson());
        long matchId = row.getMatchId();
        return switch (row.getAction()) {
            case OutboxAction.ENSURE_CONVERSATION -> imClient.ensureConversation(
                    payload.path("userA").asLong(), payload.path("userB").asLong(), matchId);
            case OutboxAction.SYSTEM_MSG -> {
                long a = payload.path("userA").asLong();
                long b = payload.path("userB").asLong();
                String text = payload.path("text").asText("你们配对了");
                boolean okA = imClient.sendMatchWelcome(b, a, matchId, text);  // 给 a 发
                boolean okB = imClient.sendMatchWelcome(a, b, matchId, text);  // 给 b 发
                yield okA && okB;
            }
            case OutboxAction.DH_OPENING -> imClient.triggerDhOpening(
                    payload.path("dh").asLong(), payload.path("bh").asLong(), matchId);
            default -> {
                log.error("Unknown outbox action: id={} action={}", row.getId(), row.getAction());
                yield true;   // 标 DONE 避免无限重试
            }
        };
    }
}
