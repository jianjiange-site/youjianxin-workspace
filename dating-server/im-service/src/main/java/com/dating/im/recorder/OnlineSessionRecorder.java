package com.dating.im.recorder;

import com.dating.im.model.entity.UserOnlineSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 在线会话落库({@code user_online_session}):上线开一行、下线回填时长。
 * 对齐 {@link JpaMessageRecorder} 的 EntityManager 风格。
 */
@Component
public class OnlineSessionRecorder {

    private static final Logger log = LoggerFactory.getLogger(OnlineSessionRecorder.class);

    @PersistenceContext
    private EntityManager em;

    /** 上线:开一行 open session({@code offline_at = NULL})。 */
    @Transactional
    public void openSession(Long userId, String platform, OffsetDateTime onlineAt) {
        em.persist(new UserOnlineSession(userId, platform, onlineAt));
        log.debug("online session opened: userId={} platform={}", userId, platform);
    }

    /**
     * 下线:回填该用户「未收口」会话(offline_at IS NULL)的 {@code offline_at} + {@code duration_seconds}。
     * 正常每用户至多一条 open 行;返回受影响行数(0 = 没找到 open 行,记 warn)。
     */
    @Transactional
    public int closeSession(Long userId, OffsetDateTime offlineAt, long durationSeconds) {
        int rows = em.createQuery(
                        "UPDATE UserOnlineSession s SET s.offlineAt = :off, "
                                + "s.durationSeconds = :dur, s.updatedAt = :now "
                                + "WHERE s.userId = :uid AND s.offlineAt IS NULL")
                .setParameter("off", offlineAt)
                .setParameter("dur", durationSeconds)
                .setParameter("now", OffsetDateTime.now(ZoneOffset.UTC))
                .setParameter("uid", userId)
                .executeUpdate();
        if (rows == 0) {
            log.warn("close online session: no open row for userId={}", userId);
        }
        return rows;
    }

    /**
     * 拉 [{@code sinceMs}, {@code untilMs}] 区间内已收口下线的 userId 列表(按 offline_at 升序)。
     * 供 ListRecentOfflineUsers RPC 用(im-proto v0.9.0)—— match-service OfflinePlanGenerator 消费。
     *
     * <p>区间两端皆闭合,DISTINCT user_id —— 同一用户同窗口内多次下线只返回一次。走 partial index
     * {@code idx_uos_offline_at ON user_online_session (offline_at) WHERE offline_at IS NOT NULL AND NOT deleted}。
     */
    public List<Long> findRecentOfflineUserIds(long sinceMs, long untilMs, int limit) {
        OffsetDateTime since = OffsetDateTime.ofInstant(Instant.ofEpochMilli(sinceMs), ZoneOffset.UTC);
        OffsetDateTime until = OffsetDateTime.ofInstant(Instant.ofEpochMilli(untilMs), ZoneOffset.UTC);
        return em.createQuery(
                        "SELECT DISTINCT s.userId FROM UserOnlineSession s "
                                + "WHERE s.offlineAt >= :since AND s.offlineAt <= :until "
                                + "AND s.deleted = false ORDER BY s.userId",
                        Long.class)
                .setParameter("since", since)
                .setParameter("until", until)
                .setMaxResults(limit)
                .getResultList();
    }
}
