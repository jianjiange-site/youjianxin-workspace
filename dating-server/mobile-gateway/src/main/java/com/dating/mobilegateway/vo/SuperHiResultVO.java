package com.dating.mobilegateway.vo;

/** SuperHi 返回。matchId != null 即匹配成功;coinsUsed=0 表示用了订阅赠送配额。 */
public record SuperHiResultVO(Long matchId,
                              Integer coinsUsed) {}
