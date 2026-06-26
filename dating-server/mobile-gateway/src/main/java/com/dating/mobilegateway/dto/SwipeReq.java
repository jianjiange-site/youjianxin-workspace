package com.dating.mobilegateway.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Swipe 请求体。
 *
 * @param targetUserId 被划的目标 userId
 * @param direction    "LEFT" 或 "RIGHT"(SUPER_HI 走独立 /super-hi 端点)
 */
@Data
public class SwipeReq {

    @NotNull
    private Long targetUserId;

    /** LEFT / RIGHT */
    @NotNull
    private String direction;
}
