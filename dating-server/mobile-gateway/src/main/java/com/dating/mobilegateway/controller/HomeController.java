package com.dating.mobilegateway.controller;

import com.dating.mobilegateway.constant.JwtClaims;
import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.exception.Result;
import com.dating.mobilegateway.service.HomeService;
import com.dating.mobilegateway.vo.HomeCardVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 主页卡片 BFF 入口 (受保护)。
//   - selfUserId 来自 JwtAuthFilter 注入的 request attr —— 不让前端传,避免越权
//   - targetUserId 从 query 取
@Slf4j
@RestController
@RequestMapping("/api/v1/home")
@RequiredArgsConstructor
@Tag(name = "Home", description = "BFF 聚合 —— 主页 / 卡片")
public class HomeController {

    private final HomeService homeService;

    @GetMapping("/card")
    @Operation(summary = "获取用户卡片 (聚合 UserProfile,后续扩展 relation/im)")
    public Result<HomeCardVO> card(@NotNull @RequestParam("targetId") Long targetId, HttpServletRequest http) {
        Object selfAttr = http.getAttribute(JwtClaims.REQUEST_ATTR_USER_ID);
        if (!(selfAttr instanceof Long selfUserId) || selfUserId <= 0) {
            throw new BizException(ErrorCodes.UNAUTHENTICATED, "missing user context");
        }
        return Result.ok(homeService.getCard(selfUserId, targetId));
    }
}
