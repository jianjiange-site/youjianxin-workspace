package com.dating.mobilegateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

// POST /api/v1/auth/login-third-party
//   - thirdPartyPlatform:proto ThirdPartyPlatform 数值 (1=Google / 2=Apple / 3=Facebook)
//   - idToken / accessToken:由 ThirdPartyTokenVerifier 校验,占位实现仅基于 token 后缀 mock
//   - googleEmail:仅 Google 走 email-as-key 兜底,其余忽略
@Data
@Schema(description = "三方登录入参 (Google / Apple / Facebook)")
public class LoginThirdPartyReq {

    @NotNull
    @Schema(description = "三方平台,proto ThirdPartyPlatform 数值。1=Google,2=Apple,3=Facebook。0/未识别值会被拒",
            example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer thirdPartyPlatform;

    @NotBlank
    @Schema(description = "三方下发的 idToken。生产由各平台 OAuth verify endpoint 校验;占位阶段按 token 后缀 mock 出稳定 userId",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String idToken;

    @Schema(description = "仅 thirdPartyPlatform=1 (Google) 时透传,作为 email-as-key 兜底落到 ResolveOrCreate;其余平台忽略",
            nullable = true)
    private String googleEmail;

    @NotBlank
    @Schema(description = "客户端设备唯一 ID。用于 auth_device 表上的设备绑定 + refresh token 设备维度撤销",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String deviceId;

    @NotNull
    @Schema(description = "客户端平台,proto Platform 数值。1=iOS,2=Android,3=Web。注意与 thirdPartyPlatform 不同",
            example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer platform;

    @Schema(description = "设备型号,例 iPhone15,3。仅用于设备画像,不参与登录判定", nullable = true)
    private String deviceModel;

    @Schema(description = "操作系统版本,例 iOS 17.5。仅画像", nullable = true)
    private String osVersion;

    @Schema(description = "App 版本号,例 1.4.2。用于强制升级判定 / 灰度", nullable = true)
    private String appVersion;

    @Schema(description = "推送 token (FCM / APNs)。落 auth_device,im-service 推离线通知时取用", nullable = true)
    private String pushToken;
}
