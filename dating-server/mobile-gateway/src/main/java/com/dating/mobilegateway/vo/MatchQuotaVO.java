package com.dating.mobilegateway.vo;

/** GetQuota 返回(订阅档位 + 当日已用 / 上限) */
public record MatchQuotaVO(String tier,
                           Integer dailyRightSwipeLimit,
                           Integer dailyRightSwipeUsed,
                           Integer dailyCardLimit,
                           Integer dailyCardUsed,
                           Integer dailySuperHiLimit,
                           Integer dailySuperHiUsed,
                           Integer superHiCoinPrice) {}
