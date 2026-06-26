package com.dating.mobilegateway.vo;

public record OrderVO(
        String orderId,
        String status,
        int statusValue
) {}
