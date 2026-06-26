package com.dating.mobilegateway.controller;

import com.dating.mobilegateway.constant.JwtClaims;
import com.dating.mobilegateway.dto.LoginDeviceReq;
import com.dating.mobilegateway.dto.LoginPhoneReq;
import com.dating.mobilegateway.dto.LoginThirdPartyReq;
import com.dating.mobilegateway.dto.RefreshTokenReq;
import com.dating.mobilegateway.dto.SendSmsCodeReq;
import com.dating.mobilegateway.exception.Result;
import com.dating.mobilegateway.service.AuthService;
import com.dating.mobilegateway.vo.LoginResultVO;
import com.dating.mobilegateway.vo.SendSmsCodeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

// REST 鉴权入口。
//   - 5 个匿名端点 (JwtAuthFilter 白名单内):send-sms-code / login-phone / login-third-party / login-device / refresh
//   - 1 个受保护端点:logout (需要 JWT 才能拿到 jti 拉黑)
//
// 客户端 IP 用 X-Forwarded-For 第一段优先,回退 HttpServletRequest.getRemoteAddr() ——
// Nginx 终结 TLS 时会带这个 header。
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "登录 / 刷新 / 登出")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/send-sms-code")
    @Operation(summary = "发送手机验证码", description = "占位实现:gateway.sms.enabled=false 时,mockCode 字段回吐 code 供联调")
    public Result<SendSmsCodeVO> sendSmsCode(@Valid @RequestBody SendSmsCodeReq req) {
        return Result.ok(authService.sendSmsCode(req.getPhone()));
    }

    @PostMapping("/login-phone")
    @Operation(summary = "手机号 + 验证码登录")
    public Result<LoginResultVO> loginPhone(@Valid @RequestBody LoginPhoneReq req, HttpServletRequest http) {
        return Result.ok(authService.loginByPhone(req, clientIp(http)));
    }

    @PostMapping("/login-third-party")
    @Operation(
            summary = "三方登录 (Google / Apple / Facebook,占位 mock)",
            description = """
                    入参: LoginThirdPartyReq
                      - thirdPartyPlatform (必填,1=Google / 2=Apple / 3=Facebook)
                      - idToken (必填,三方下发的 idToken;占位阶段按 token 后缀 mock 出稳定 userId)
                      - googleEmail (仅 Google 透传,email-as-key 兜底)
                      - deviceId + platform (必填,客户端设备 + 平台 1=iOS/2=Android/3=Web)
                      - deviceModel / osVersion / appVersion / pushToken (可选,设备画像 + 推送)
                    出参: LoginResultVO
                      - accessToken + refreshToken (后者仅此一次明文返回)
                      - userId / pending (onboarding 是否未完成) / newlyCreated (是否首次注册)
                      - accessExpiresAtMs / refreshExpiresAtMs (UTC epoch ms,前端兜底主动刷新)
                    """)
    public Result<LoginResultVO> loginThirdParty(@Valid @RequestBody LoginThirdPartyReq req, HttpServletRequest http) {
        return Result.ok(authService.loginByThirdParty(req, clientIp(http)));
    }

    @PostMapping("/login-device")
    @Operation(
            summary = "设备匿名登录 (G5 端到端 acceptance 路径)",
            description = """
                    入参: LoginDeviceReq
                      - deviceId (必填,同一 deviceId + platform 命中既有账号,首次自动注册)
                      - platform (必填,1=iOS / 2=Android / 3=Web)
                      - deviceModel / osVersion / appVersion / pushToken (可选,设备画像 + 推送)
                    出参: LoginResultVO
                      - accessToken + refreshToken (后者仅此一次明文返回)
                      - userId / pending (onboarding 是否未完成) / newlyCreated (是否首次注册)
                      - accessExpiresAtMs / refreshExpiresAtMs (UTC epoch ms,前端兜底主动刷新)
                    """)
    public Result<LoginResultVO> loginDevice(@Valid @RequestBody LoginDeviceReq req, HttpServletRequest http) {
        return Result.ok(authService.loginByDevice(req, clientIp(http)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "用 refresh token 换新 access+refresh 对 (轮换,旧 refresh 标 used_at)")
    public Result<LoginResultVO> refresh(@Valid @RequestBody RefreshTokenReq req, HttpServletRequest http) {
        return Result.ok(authService.refresh(req.getRefreshToken(), clientIp(http)));
    }

    @PostMapping("/logout")
    @Operation(summary = "登出 —— 拉黑当前 access jti + 撤销当前用户当前设备所有 refresh")
    public Result<Void> logout(HttpServletRequest http) {
        Object userIdAttr = http.getAttribute(JwtClaims.REQUEST_ATTR_USER_ID);
        Object deviceIdAttr = http.getAttribute(JwtClaims.REQUEST_ATTR_DEVICE_ID);
        Object jtiAttr = http.getAttribute(JwtClaims.REQUEST_ATTR_JTI);
        Object expAttr = http.getAttribute(JwtClaims.REQUEST_ATTR_ACCESS_EXP);
        Long userId = userIdAttr instanceof Long l ? l : null;
        String deviceId = deviceIdAttr instanceof String s ? s : null;
        String jti = jtiAttr instanceof String s ? s : null;
        OffsetDateTime exp = expAttr instanceof OffsetDateTime t ? t : null;
        authService.logout(userId, deviceId, jti, exp);
        return Result.ok();
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }
}
