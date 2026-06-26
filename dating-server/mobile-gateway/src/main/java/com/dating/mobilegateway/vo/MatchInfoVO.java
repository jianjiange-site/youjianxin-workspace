package com.dating.mobilegateway.vo;

import java.util.List;

/** 单个匹配信息 */
public record MatchInfoVO(Long matchId,
                          Long partnerUserId,
                          String partnerNickname,
                          List<String> partnerPhotoKeys,
                          Long matchedAtMs,
                          String source) {}
