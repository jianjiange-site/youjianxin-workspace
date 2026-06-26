package com.dating.mobilegateway.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// UserIdentityService 三种 ResolveOrCreate 出参 → VO。
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResolveOrCreateVO {
    private Long userId;
    private Boolean pending;
    private Boolean newlyCreated;
}
