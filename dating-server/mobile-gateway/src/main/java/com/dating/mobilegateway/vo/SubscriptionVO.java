package com.dating.mobilegateway.vo;

public record SubscriptionVO(
        String tier,
        int tierValue,
        boolean isActive,
        Long expiresAtSeconds
) {}
