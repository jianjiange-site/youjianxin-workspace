package com.dating.match.service;

import com.dating.match.constant.LockKeys;
import com.dating.match.exception.BizException;
import com.dating.match.exception.ErrorCodes;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Swipe 串行化锁模板:统一 (userId, targetUserId) 维度的 Redisson 锁取/释放语义。
 *
 * <p>详见 docs §6.6:同一 (userId, targetUserId) 单飞,wait/lease 由 {@link LockKeys} 控制。
 */
@Component
@RequiredArgsConstructor
public class SwipeLockTemplate {

    private final RedissonClient redisson;

    public <T> T runUnderSwipeLock(long userId, long targetUserId, Supplier<T> action) {
        RLock lock = redisson.getLock(LockKeys.swipe(userId, targetUserId));
        boolean locked;
        try {
            locked = lock.tryLock(LockKeys.SWIPE_WAIT.toMillis(),
                    LockKeys.SWIPE_LEASE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCodes.CONCURRENT_SWIPE, "interrupted");
        }
        if (!locked) {
            throw new BizException(ErrorCodes.CONCURRENT_SWIPE, "lock busy");
        }
        try {
            return action.get();
        } finally {
            try { lock.unlock(); } catch (Exception ignore) {}
        }
    }
}
