package com.dating.mobilegateway.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// CheckBan 出参 → VO。reason 与 user.proto BanReason 数值对齐。
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CheckBanVO {
    private Boolean banned;
    private Integer reason;
    private Long bannedAtMs;
    private String message;
}
