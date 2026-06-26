package com.dating.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record WithdrawReq(
        @Positive long amountCent,
        @NotBlank String accountId,
        @NotBlank String idempotencyKey
) {}
