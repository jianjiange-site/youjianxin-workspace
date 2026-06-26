package com.dating.mobilegateway.vo;

public record BalanceVO(
        String currency,
        long availableBalanceCent,
        long frozenBalanceCent
) {}
