package com.dating.mobilegateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

// PATCH /api/v1/profile:日常资料更新 (proto optional patch 语义)。
//   - 所有字段可选;null = 不更新 (proto3 hasXxx 跳过 set)
//   - 不可更新字段:gender / birthday (一次性在 onboarding 时定;改性别/生日另起独立端点)
//   - 自定义头像走 /api/v1/upload/presign + /confirm;系统预设头像走 /onboarding.defaultAvatarObjectKey
@Data
@Schema(description = "更新用户资料入参 (PATCH;所有字段可选,null 不更新)。不可改 gender / birthday")
public class UpdateProfileReq {

    @Schema(description = "昵称", nullable = true)
    private String nickname;

    @Schema(description = "年龄;0 表示未填写", example = "25", nullable = true)
    private Integer age;

    @Schema(description = "身高 (cm);0 表示未填写", example = "175", nullable = true)
    private Integer height;

    @Schema(description = "个人简介", nullable = true)
    private String bio;

    @Schema(description = "职业", nullable = true)
    private String occupation;

    @Schema(description = "学历", nullable = true)
    private String education;

    @Schema(description = "偏好城市 / 地区", nullable = true)
    private String location;
}
