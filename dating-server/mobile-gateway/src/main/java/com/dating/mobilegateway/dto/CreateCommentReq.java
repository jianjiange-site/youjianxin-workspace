package com.dating.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateCommentReq(
        @NotNull Long postId,
        @NotBlank String content
) {}
