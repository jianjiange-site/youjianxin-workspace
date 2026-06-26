package com.dating.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * SuperHi 请求体。
 *
 * @param targetUserId    被 SuperHi 的目标 userId
 * @param clientRequestId 客户端生成的 UUID,服务端透传给 payment.ConsumeCoins.idempotency_key
 *                        防止网络重试导致重复扣 100 金币
 */
@Data
public class SuperHiReq {

    @NotNull
    private Long targetUserId;

    @NotBlank
    private String clientRequestId;
}
