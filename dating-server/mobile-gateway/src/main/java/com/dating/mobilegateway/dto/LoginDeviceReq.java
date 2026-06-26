package com.dating.mobilegateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

// POST /api/v1/auth/login-device:设备登录 (匿名快速注册),无外部依赖 —— G5 acceptance 用此路径冒烟。
@Data
@Schema(description = "设备匿名登录入参 —— deviceId 作为唯一身份标识,无需短信 / 三方凭据")
public class LoginDeviceReq {

    @NotBlank
    @Schema(description = "客户端设备唯一 ID。同一 deviceId + platform 复登命中既有账号,首次登录会自动注册",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String deviceId;

    @NotNull
    @Schema(description = "客户端平台,proto Platform 数值。1=iOS,2=Android,3=Web",
            example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer platform;

    @Schema(description = "设备型号,例 iPhone15,3。仅用于设备画像", nullable = true)
    private String deviceModel;

    @Schema(description = "操作系统版本,例 iOS 17.5。仅画像", nullable = true)
    private String osVersion;

    @Schema(description = "App 版本号,例 1.4.2。用于强制升级判定 / 灰度", nullable = true)
    private String appVersion;

    @Schema(description = "推送 token (FCM / APNs)。落 auth_device,im-service 推离线通知时取用", nullable = true)
    private String pushToken;
}
