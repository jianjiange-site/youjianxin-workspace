package com.dating.mobilegateway.controller;

import com.dating.mobilegateway.client.ImClient;
import com.dating.mobilegateway.constant.JwtClaims;
import com.dating.mobilegateway.entity.AuthDevice;
import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.exception.Result;
import com.dating.mobilegateway.manager.AuthDeviceManager;
import com.dating.mobilegateway.vo.CallTokenVO;
import com.dating.mobilegateway.vo.ImTokenVO;
import com.dating.youjianxin.proto.im.GenerateCallTokenResponse;
import com.dating.youjianxin.proto.im.GetImTokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM token, LiveKit call token endpoints for mobile clients.
 *
 * <p>userId 一律从 JWT 上下文取,不从 query 参数读 —— 防越权(攻击者用自己的 access token 拿别人的 IM token)。
 */
@RestController
@RequestMapping("/api/v1")
public class ImTokenController {

    private static final Logger log = LoggerFactory.getLogger(ImTokenController.class);

    private final ImClient imClient;
    private final AuthDeviceManager authDeviceManager;

    public ImTokenController(ImClient imClient, AuthDeviceManager authDeviceManager) {
        this.imClient = imClient;
        this.authDeviceManager = authDeviceManager;
    }

    /** Get IM token for WebSocket connection to OpenIM Server. */
    @GetMapping("/im/token")
    public Result<ImTokenVO> getImToken(HttpServletRequest http) {
        long userId = callerUserId(http);
        int platform = resolvePlatform(userId, http);
        GetImTokenResponse resp = imClient.getImToken(String.valueOf(userId), platform);
        log.info("IM token issued: userId={}, platform={}", userId, platform);
        return Result.ok(new ImTokenVO(resp.getUserId(), resp.getImToken()));
    }

    /** Generate a LiveKit token for a 1v1 call. */
    @PostMapping("/call/token")
    public Result<CallTokenVO> getCallToken(@RequestParam String peerId, HttpServletRequest http) {
        long userId = callerUserId(http);
        GenerateCallTokenResponse resp = imClient.generateCallToken(String.valueOf(userId), peerId);
        if (resp.getToken().isEmpty()) {
            throw new BizException(ErrorCodes.UPSTREAM_UNAVAILABLE, "LiveKit not configured");
        }
        return Result.ok(new CallTokenVO(resp.getToken()));
    }

    private static long callerUserId(HttpServletRequest http) {
        Object attr = http.getAttribute(JwtClaims.REQUEST_ATTR_USER_ID);
        if (attr instanceof Long l) return l;
        throw new BizException(ErrorCodes.TOKEN_INVALID, "missing user id in JWT context");
    }

    private int resolvePlatform(long userId, HttpServletRequest http) {
        Object didAttr = http.getAttribute(JwtClaims.REQUEST_ATTR_DEVICE_ID);
        if (didAttr instanceof String deviceId && !deviceId.isBlank()) {
            AuthDevice device = authDeviceManager.findByUserAndDevice(userId, deviceId);
            if (device != null && device.getPlatform() != null) {
                return device.getPlatform();
            }
        }
        log.debug("platform not resolved from device, fallback 0: userId={}", userId);
        return 0;
    }
}
