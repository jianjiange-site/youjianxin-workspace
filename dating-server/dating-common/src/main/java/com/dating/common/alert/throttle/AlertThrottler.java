package com.dating.common.alert.throttle;

import com.dating.common.alert.AlertEvent;

// 限流入口:tryAcquire 走完整限流;tryAcquireGlobalOnly 给 critical 等级跳过签名窗口。
public class AlertThrottler {

    public enum Decision { ACCEPT, DROP_SIGNATURE, DROP_GLOBAL }

    private final ThrottleRegistry registry;
    private final TokenBucket globalBucket;

    public AlertThrottler(ThrottleRegistry registry, TokenBucket globalBucket) {
        this.registry = registry;
        this.globalBucket = globalBucket;
    }

    public Decision tryAcquire(AlertEvent e) {
        if (!globalBucket.tryTake()) return Decision.DROP_GLOBAL;
        SignatureWindow.Outcome outcome = registry.recordAndCheck(ExceptionSignature.of(e), e);
        return outcome == SignatureWindow.Outcome.ACCEPT ? Decision.ACCEPT : Decision.DROP_SIGNATURE;
    }

    public Decision tryAcquireGlobalOnly(AlertEvent e) {
        return globalBucket.tryTake() ? Decision.ACCEPT : Decision.DROP_GLOBAL;
    }
}
