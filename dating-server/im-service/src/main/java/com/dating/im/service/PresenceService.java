package com.dating.im.service;

import com.dating.im.config.PresenceProperties;
import com.dating.im.manager.PresenceRedisManager;
import com.dating.im.model.event.UserOfflineEvent;
import com.dating.im.model.event.UserOnlineEvent;
import com.dating.im.recorder.OnlineSessionRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * 在线状态编排:维护在线集合(Redis ZSet)+ 在线时长落库(PG {@code user_online_session})。
 *
 * <p><b>简单版,忽略多设备</b>:只按 {@code userId} 计。OpenIM 实际按 platformID 逐设备触发
 * online/offline,本版接受「某设备下线即把整个用户移出在线集」的不精确(用 ZADD NX 保证每用户
 * 至多一条 open 会话,后到的 online 与早到的 offline 不重复开/关行)。
 *
 * <p>Redis 集合追踪所有用户(用于在线人数);PG 仅落数字 userId(便于其它业务消费)。
 */
@Service
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    private final PresenceRedisManager redis;
    private final OnlineSessionRecorder recorder;
    private final PresenceProperties props;

    public PresenceService(PresenceRedisManager redis, OnlineSessionRecorder recorder,
                           PresenceProperties props) {
        this.redis = redis;
        this.recorder = recorder;
        this.props = props;
    }

    /** 用户上线:ZADD NX 加入在线集合;首个上线才开一行 open session。 */
    public void online(UserOnlineEvent e) {
        boolean firstOnline = redis.markOnline(e.userId(), e.timestamp());
        if (!firstOnline) {
            log.debug("user already online, skip open session: userId={} platform={}",
                    e.userId(), e.platform());
            return;
        }
        Long userId = parseUserId(e.userId());
        if (userId != null) {
            recorder.openSession(userId, e.platform(), toUtc(e.timestamp()));
        }
    }

    /** 用户下线:算时长回填 open session,再从在线集合移除。 */
    public void offline(UserOfflineEvent e) {
        Long sinceMs = redis.onlineSince(e.userId());
        if (sinceMs == null) {
            log.warn("offline without online in set, skip: userId={} platform={}",
                    e.userId(), e.platform());
            return;
        }
        Long userId = parseUserId(e.userId());
        if (userId != null) {
            long durationSeconds = Math.max(0L, (e.timestamp() - sinceMs) / 1000L);
            recorder.closeSession(userId, toUtc(e.timestamp()), durationSeconds);
            log.info("user offline: userId={} durationSeconds={}", e.userId(), durationSeconds);
        }
        redis.remove(e.userId());
    }

    /** 当前在线人数(去重用户数)。 */
    public long onlineCount() {
        return redis.onlineCount();
    }

    /**
     * 孤儿兜底:把只上线没下线、在线超过阈值的会话收口 + 移出在线集。不知道真实下线时刻,
     * 按阈值封顶时长收口(offline_at = online_at + 阈值)。返回收口数量。
     */
    public int sweepStale() {
        long maxMs = props.getSweep().getMaxOnlineHours() * 3600_000L;
        long cutoffMs = Instant.now().toEpochMilli() - maxMs;
        Set<String> stale = redis.findOnlineBefore(cutoffMs);
        int closed = 0;
        for (String uid : stale) {
            Long sinceMs = redis.onlineSince(uid);
            if (sinceMs == null) {
                continue; // 已被并发收口
            }
            Long userId = parseUserId(uid);
            if (userId != null) {
                recorder.closeSession(userId, toUtc(sinceMs + maxMs), maxMs / 1000L);
            }
            redis.remove(uid);
            closed++;
        }
        if (closed > 0) {
            log.info("presence sweep closed {} stale online session(s)", closed);
        }
        return closed;
    }

    private OffsetDateTime toUtc(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    /** OpenIM userID 预期是数字业务 userId 的字符串;非数字时记 warn 并跳过 PG(Redis 仍计数)。 */
    private Long parseUserId(String userId) {
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException ex) {
            log.warn("non-numeric IM userId, skip presence persistence: userId={}", userId);
            return null;
        }
    }
}
