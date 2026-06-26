package com.dating.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BindAccountReq(
        @NotNull Integer type,
        @NotBlank String accountIdentifier,
        @NotBlank String holderName
) {}
