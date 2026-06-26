package com.dating.mobilegateway.vo;

public record CoinLedgerVO(
        long id,
        long userId,
        String type,
        long amount,
        long balanceAfter,
        String reason
) {}
