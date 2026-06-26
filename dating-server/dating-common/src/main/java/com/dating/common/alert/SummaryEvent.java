package com.dating.common.alert;

import java.time.Instant;

// 窗口过期时 ThrottleRegistry 生成的截流摘要。走 sender 队列但跳过签名窗口与令牌桶。
public record SummaryEvent(
        long signature,
        String exceptionClass,
        String topFrame,
        int accepted,
        int dropped,
        Instant firstSeen,
        Instant lastSeen,
        java.time.Duration windowDuration,
        String env,
        String service,
        String host
) {
}
