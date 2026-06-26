package com.dating.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyPaymentReq(
        @NotBlank String orderId,
        @NotBlank String receiptData,
        String signature,
        String extOrderId,
        int paymentMethod
) {}
