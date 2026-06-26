package com.dating.match.service;

import com.dating.match.client.UserClient;
import com.dating.match.constant.OutboxAction;
import com.dating.match.entity.Match;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.MatchOutboxManager;
import com.dating.youjianxin.proto.user.UserProfile;
import com.dating.youjianxin.proto.user.UserType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Match 创建 + 副作用 outbox 编排。
 *
 * <p>详见 docs §5.3 Match 创建副作用。
 *
 * <p>事务里:写 match 行 + 写 outbox 3 条(ENSURE_CONVERSATION / SYSTEM_MSG / DH_OPENING)。
 * 后台 MatchOutboxRetry 异步消费 + retry。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {

    private final MatchManager matchManager;
    private final MatchOutboxManager outboxManager;
    private final UserClient userClient;

    /**
     * 创建 match;已 matched 时不重复写 outbox,直接返回 existing 行(已在 MatchManager 打 ERROR 日志)。
     */
    @Transactional
    public Match createMatch(long userIdA, long userIdB, String source) {
        MatchManager.Result r = matchManager.insertOrFindExisting(userIdA, userIdB, source);
        if (r.existed()) {
            return r.match();
        }
        // 副作用 outbox 3 条;DH_OPENING 只在 BH-DH 配对时写,需要查 user_type
        Map<Long, UserType> userTypeMap = lookupUserTypes(List.of(userIdA, userIdB));
        UserType ta = userTypeMap.getOrDefault(userIdA, UserType.USER_TYPE_BH);
        UserType tb = userTypeMap.getOrDefault(userIdB, UserType.USER_TYPE_BH);

        long matchId = r.match().getId();
        outboxManager.enqueue(matchId, OutboxAction.ENSURE_CONVERSATION,
                payloadEnsure(userIdA, userIdB));
        outboxManager.enqueue(matchId, OutboxAction.SYSTEM_MSG,
                payloadSystemMsg(userIdA, userIdB));
        // DH_OPENING:如果一方是 DH,触发 ai-chat 开场白(DH→BH)
        if (ta == UserType.USER_TYPE_DH && tb == UserType.USER_TYPE_BH) {
            outboxManager.enqueue(matchId, OutboxAction.DH_OPENING,
                    payloadDhOpening(userIdA, userIdB));
        } else if (tb == UserType.USER_TYPE_DH && ta == UserType.USER_TYPE_BH) {
            outboxManager.enqueue(matchId, OutboxAction.DH_OPENING,
                    payloadDhOpening(userIdB, userIdA));
        }
        return r.match();
    }

    private Map<Long, UserType> lookupUserTypes(List<Long> userIds) {
        Map<Long, UserType> out = new java.util.HashMap<>(userIds.size());
        try {
            List<UserProfile> profiles = userClient.batchGetProfiles(userIds);
            for (UserProfile p : profiles) {
                out.put(p.getUserId(), p.getUserType());
            }
        } catch (Exception e) {
            log.warn("lookupUserTypes failed userIds={};DH_OPENING 决策回退保守(默认 BH),不发开场白", userIds, e);
        }
        return out;
    }

    private String payloadEnsure(long a, long b) {
        return "{\"userA\":" + a + ",\"userB\":" + b + "}";
    }

    private String payloadSystemMsg(long a, long b) {
        return "{\"userA\":" + a + ",\"userB\":" + b + ",\"text\":\"你们配对了\"}";
    }

    private String payloadDhOpening(long dh, long bh) {
        return "{\"dh\":" + dh + ",\"bh\":" + bh + "}";
    }
}
