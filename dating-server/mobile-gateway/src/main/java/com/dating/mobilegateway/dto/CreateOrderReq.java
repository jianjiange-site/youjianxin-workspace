package com.dating.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderReq(
        @NotBlank String productId,
        @NotNull Integer paymentMethod,
        @NotBlank String currency,
        @NotBlank String platform
) {}
