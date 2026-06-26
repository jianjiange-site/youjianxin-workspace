package com.dating.mobilegateway.vo;

import java.util.List;

/** 我的匹配列表(分页) */
public record MatchListVO(List<MatchInfoVO> matches, String nextPageToken) {}
