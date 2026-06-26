package com.dating.im.service;

import com.dating.im.config.PresenceProperties;
import com.dating.im.manager.PresenceRedisManager;
import com.dating.im.model.event.UserOfflineEvent;
import com.dating.im.model.event.UserOnlineEvent;
import com.dating.im.recorder.OnlineSessionRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PresenceService} — 简单版(按 userId、忽略多设备)online/offline 落库语义。
 */
@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    @Mock private PresenceRedisManager redis;
    @Mock private OnlineSessionRecorder recorder;
    @Spy private PresenceProperties props = new PresenceProperties();

    @InjectMocks private PresenceService service;

    private static UserOnlineEvent online(String userId, String platform, long ts) {
        return new UserOnlineEvent("openim", "callbackUserOnlineCommand", userId, platform, ts);
    }

    private static UserOfflineEvent offline(String userId, String platform, long ts) {
        return new UserOfflineEvent("openim", "callbackUserOfflineCommand", userId, platform, ts);
    }

    @Test
    void firstOnlineOpensOneSession() {
        when(redis.markOnline("100", 1000L)).thenReturn(true);

        service.online(online("100", "iOS", 1000L));

        OffsetDateTime expectedOnlineAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(1000L), ZoneOffset.UTC);
        verify(recorder).openSession(eq(100L), eq("iOS"), eq(expectedOnlineAt));
    }

    @Test
    void duplicateOnlineDoesNotOpenSession() {
        when(redis.markOnline("100", 2000L)).thenReturn(false); // 已在线(重连 / 第二个设备)

        service.online(online("100", "Android", 2000L));

        verify(recorder, never()).openSession(any(), anyString(), any());
    }

    @Test
    void nonNumericUserIdStillTrackedInRedisButNotPersisted() {
        when(redis.markOnline("imAdmin", 1000L)).thenReturn(true);

        service.online(online("imAdmin", "Web", 1000L));

        verify(redis).markOnline("imAdmin", 1000L); // Redis 仍计数
        verify(recorder, never()).openSession(any(), anyString(), any()); // 非数字跳过 PG
    }

    @Test
    void offlineComputesDurationClosesAndRemoves() {
        when(redis.onlineSince("100")).thenReturn(1000L);

        service.offline(offline("100", "iOS", 6000L)); // 5s

        OffsetDateTime expectedOfflineAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(6000L), ZoneOffset.UTC);
        verify(recorder).closeSession(eq(100L), eq(expectedOfflineAt), eq(5L));
        verify(redis).remove("100");
    }

    @Test
    void offlineWithoutOnlineIsNoOp() {
        when(redis.onlineSince("100")).thenReturn(null); // 不在在线集合

        service.offline(offline("100", "iOS", 6000L));

        verify(recorder, never()).closeSession(any(), any(), anyLong());
        verify(redis, never()).remove(anyString());
    }

    @Test
    void sweepClosesStaleSessionCappedAtThreshold() {
        // maxOnlineHours 默认 26h
        long maxMs = 26L * 3600_000L;
        when(redis.findOnlineBefore(anyLong())).thenReturn(Set.of("100"));
        when(redis.onlineSince("100")).thenReturn(1L);

        int closed = service.sweepStale();

        assertEquals(1, closed);
        OffsetDateTime cappedOfflineAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(1L + maxMs), ZoneOffset.UTC);
        verify(recorder).closeSession(eq(100L), eq(cappedOfflineAt), eq(maxMs / 1000L));
        verify(redis).remove("100");
    }
}
