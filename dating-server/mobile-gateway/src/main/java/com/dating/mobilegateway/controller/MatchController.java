package com.dating.mobilegateway.controller;

import com.dating.mobilegateway.constant.JwtClaims;
import com.dating.mobilegateway.dto.SuperHiReq;
import com.dating.mobilegateway.dto.SwipeReq;
import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.exception.Result;
import com.dating.mobilegateway.service.MatchService;
import com.dating.mobilegateway.vo.MatchFeedVO;
import com.dating.mobilegateway.vo.MatchListVO;
import com.dating.mobilegateway.vo.MatchQuotaVO;
import com.dating.mobilegateway.vo.SuperHiResultVO;
import com.dating.mobilegateway.vo.SwipeResultVO;
import com.dating.mobilegateway.vo.VisitListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/match/* 划卡 / 匹配 / SuperHi / 我的匹配 / 配额。
 *
 * <p>selfUserId 来自 JwtAuthFilter 注入的 request attr,不让前端传防越权。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/match")
@RequiredArgsConstructor
@Tag(name = "Match", description = "划卡 / 匹配 / SuperHi / 配额")
public class MatchController {

    private final MatchService matchService;

    @GetMapping("/feed")
    @Operation(summary = "拉当日 feed 下一批(App 端固定 count=5;LPOP 即消费 + 二次过滤)")
    public Result<MatchFeedVO> feed(@RequestParam(value = "count", defaultValue = "5") int count,
                                    HttpServletRequest http) {
        requireSelf(http);
        return Result.ok(matchService.getFeed(count));
    }

    @PostMapping("/swipe")
    @Operation(summary = "划卡(LEFT / RIGHT)")
    public Result<SwipeResultVO> swipe(@Valid @RequestBody SwipeReq req, HttpServletRequest http) {
        requireSelf(http);
        return Result.ok(matchService.swipe(req.getTargetUserId(), req.getDirection()));
    }

    @PostMapping("/super-hi")
    @Operation(summary = "SuperHi(订阅赠送 1 次/天,否则扣 100 金币;BH/DH 一律立即匹配)")
    public Result<SuperHiResultVO> superHi(@Valid @RequestBody SuperHiReq req, HttpServletRequest http) {
        requireSelf(http);
        return Result.ok(matchService.superHi(req.getTargetUserId(), req.getClientRequestId()));
    }

    @GetMapping("/matches")
    @Operation(summary = "我的匹配列表(分页)")
    public Result<MatchListVO> matches(@RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
                                       @RequestParam(value = "pageToken", required = false) String pageToken,
                                       HttpServletRequest http) {
        requireSelf(http);
        return Result.ok(matchService.listMatches(pageSize, pageToken));
    }

    @GetMapping("/quota")
    @Operation(summary = "配额查询(订阅档位 + 当日已用 / 剩余)")
    public Result<MatchQuotaVO> quota(HttpServletRequest http) {
        requireSelf(http);
        return Result.ok(matchService.getQuota());
    }

    @PostMapping("/visit/{targetUserId}")
    @Operation(summary = "记录主页访问(App 端打开他人主页时调用;服务端 UPSERT 累加 visit_count,失败 fail-open)")
    public Result<Boolean> visit(@PathVariable long targetUserId, HttpServletRequest http) {
        requireSelf(http);
        matchService.recordVisit(targetUserId);
        return Result.ok(true);
    }

    @GetMapping("/visits")
    @Operation(summary = "查询访问过我主页的用户列表(按最近访问倒序,visitCount 为累计访问次数)")
    public Result<VisitListVO> visits(@RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
                                      @RequestParam(value = "pageToken", required = false) String pageToken,
                                      HttpServletRequest http) {
        requireSelf(http);
        return Result.ok(matchService.listVisitsOfMe(pageSize, pageToken));
    }

    private static void requireSelf(HttpServletRequest http) {
        Object selfAttr = http.getAttribute(JwtClaims.REQUEST_ATTR_USER_ID);
        if (!(selfAttr instanceof Long selfUserId) || selfUserId <= 0) {
            throw new BizException(ErrorCodes.UNAUTHENTICATED, "missing user context");
        }
    }
}
