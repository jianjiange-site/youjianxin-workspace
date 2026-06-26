package com.dating.im.manager;

import com.dating.im.constant.PresenceKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PresenceRedisManager#rangeByScore} —— ZRANGEBYSCORE 包装 + limit 夹紧 +
 * 非数字 userId 静默跳过(与 PresenceService.parseUserId 行为一致)。
 */
@ExtendWith(MockitoExtension.class)
class PresenceRedisManagerTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ZSetOperations<String, String> zset;

    @InjectMocks private PresenceRedisManager manager;

    private void stubRangeByScore(long sinceMs, long untilMs, int limit, Set<String> result) {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rangeByScore(PresenceKeys.ONLINE_ZSET, (double) sinceMs, (double) untilMs, 0L, (long) limit))
                .thenReturn(result);
    }

    @Test
    void rangeByScoreParsesNumericMembersAndKeepsOrder() {
        stubRangeByScore(1000L, 2000L, 5000, ordered("100", "200", "300"));

        List<Long> got = manager.rangeByScore(1000L, 2000L, 0);

        assertEquals(List.of(100L, 200L, 300L), got);
    }

    @Test
    void rangeByScoreSkipsNonNumericMembers() {
        stubRangeByScore(1000L, 2000L, 5000, ordered("100", "imAdmin", "200"));

        List<Long> got = manager.rangeByScore(1000L, 2000L, 0);

        assertEquals(List.of(100L, 200L), got);
    }

    @Test
    void rangeByScoreReturnsEmptyOnNullOrEmptyMembers() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rangeByScore(eq(PresenceKeys.ONLINE_ZSET), eq(1000.0), eq(2000.0), eq(0L), eq(5000L)))
                .thenReturn(null);

        assertTrue(manager.rangeByScore(1000L, 2000L, 0).isEmpty());
    }

    @Test
    void rangeByScoreClampsLimitToHardMax() {
        stubRangeByScore(1L, 2L, 50_000, Set.of());

        manager.rangeByScore(1L, 2L, 999_999);

        verify(zset).rangeByScore(PresenceKeys.ONLINE_ZSET, 1.0, 2.0, 0L, 50_000L);
    }

    @Test
    void rangeByScoreUsesDefaultWhenLimitNonPositive() {
        stubRangeByScore(1L, 2L, 5000, Set.of());

        manager.rangeByScore(1L, 2L, -1);

        verify(zset).rangeByScore(PresenceKeys.ONLINE_ZSET, 1.0, 2.0, 0L, 5000L);
    }

    private static Set<String> ordered(String... members) {
        return new LinkedHashSet<>(List.of(members));
    }
}
