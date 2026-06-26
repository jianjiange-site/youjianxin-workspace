package com.dating.mobilegateway.vo;

import java.util.List;

/** "Visits of me" 列表(分页;按 lastVisitedAtMs 倒序) */
public record VisitListVO(List<VisitInfoVO> visits, String nextPageToken) {}
