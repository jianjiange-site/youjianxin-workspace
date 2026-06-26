package com.dating.mobilegateway.vo;

import java.util.List;

/** GetTodayFeed 返回 */
public record MatchFeedVO(List<MatchCardVO> cards, Boolean exhausted) {}
