package com.dating.mobilegateway.vo;

/** 单条访问记录(谁访问了我;UPSERT 累加 visitCount) */
public record VisitInfoVO(Long visitId,
                          Long fromUserId,
                          Integer visitCount,
                          Long firstVisitedAtMs,
                          Long lastVisitedAtMs) {}
