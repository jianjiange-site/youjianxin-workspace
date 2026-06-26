package com.dating.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

// POST /api/v1/auth/login-phone:手机号 + 验证码登录。deviceId 在 header X-Device-Id 或 body 透传都可,
// 这里仍要求 body 必填,避免歧义。platform 用 proto Platform 数值 (1=iOS / 2=Android / 3=Web)。
@Data
public class LoginPhoneReq {

    @NotBlank
    @Pattern(regexp = "^\\+?[1-9][0-9]{6,14}$", message = "phone must be E.164")
    private String phone;

    @NotBlank
    @Pattern(regexp = "^[0-9]{4,8}$", message = "smsCode must be 4-8 digits")
    private String smsCode;

    @NotBlank
    private String deviceId;

    @NotNull
    private Integer platform;

    private String deviceModel;
    private String osVersion;
    private String appVersion;
    private String pushToken;
}
