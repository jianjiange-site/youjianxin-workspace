package com.dating.mobilegateway.vo;

public record HistoryVO(
        String id,
        long amountCent,
        String status,
        String type,
        Long createTimeSeconds
) {}
