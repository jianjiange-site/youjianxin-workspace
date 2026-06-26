package com.dating.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;

public record CreatePostReq(
        @NotBlank String content,
        java.util.List<String> imageKeys
) {}
