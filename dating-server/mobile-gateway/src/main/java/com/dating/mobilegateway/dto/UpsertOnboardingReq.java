package com.dating.mobilegateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

// POST /api/v1/profile/onboarding
//   - 所有字段可选;null = 不更新;user-service 决定 pending=false 的判定门槛
//   - gender 用 proto Gender 数值 (0=UNSPECIFIED / 1=MALE / 2=FEMALE),非法值经 forNumber 转 null 后忽略
//   - defaultAvatarObjectKey 仅用于选系统预设头像;自定义上传走 /api/v1/upload/presign + /confirm
@Data
@Schema(description = "Onboarding 完善资料入参 (所有字段可选;null 不更新)")
public class UpsertOnboardingReq {

    @Schema(description = "昵称", nullable = true)
    private String nickname;

    @Schema(description = "性别,proto Gender 数值。0=UNSPECIFIED / 1=MALE / 2=FEMALE;非法值忽略",
            example = "1", nullable = true)
    private Integer gender;

    @Schema(description = "生日,yyyy-MM-dd (兼容 yyyy/MM/dd)", example = "1998-05-20", nullable = true)
    private String birthday;

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

    @Schema(description = "默认头像 object_key (系统预设头像库);为空走系统默认头像。" +
            "自定义上传不走这里,走 /api/v1/upload/presign + /confirm", nullable = true)
    private String defaultAvatarObjectKey;
}
