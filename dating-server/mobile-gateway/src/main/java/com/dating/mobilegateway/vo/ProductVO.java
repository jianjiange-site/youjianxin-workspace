package com.dating.mobilegateway.vo;

public record ProductVO(
        String productId,
        String title,
        String description,
        long priceCent,
        String currency
) {}
