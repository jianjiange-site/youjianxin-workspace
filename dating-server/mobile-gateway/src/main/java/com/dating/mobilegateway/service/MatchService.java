package com.dating.mobilegateway.service;

import com.dating.mobilegateway.vo.MatchFeedVO;
import com.dating.mobilegateway.vo.MatchListVO;
import com.dating.mobilegateway.vo.MatchQuotaVO;
import com.dating.mobilegateway.vo.SuperHiResultVO;
import com.dating.mobilegateway.vo.SwipeResultVO;
import com.dating.mobilegateway.vo.VisitListVO;

/**
 * /api/v1/match/* 路由的 BFF 编排接口。
 *
 * <p>所有方法的 caller userId 由 Controller 从 JwtAuthFilter 注入的 request attr 拿到,
 * 再通过 GrpcClientMetadataInterceptor 透传 x-user-id metadata 给 match-service。
 * 服务方法签名不出现 callerUserId(参考 HomeService 的设计)。
 */
public interface MatchService {

    MatchFeedVO getFeed(int count);

    SwipeResultVO swipe(long targetUserId, String direction);

    SuperHiResultVO superHi(long targetUserId, String clientRequestId);

    MatchListVO listMatches(int pageSize, String pageToken);

    MatchQuotaVO getQuota();

    /**
     * 记录主页访问;App 端打开他人主页时调用。
     * 自访问短路 + 失败 fail-open 由 match-service 端兜底,这里只透传。
     */
    void recordVisit(long targetUserId);

    /** "Visits of me" 列表:谁访问过我的主页(按最近访问倒序,UPSERT 累加 visitCount) */
    VisitListVO listVisitsOfMe(int pageSize, String pageToken);
}
