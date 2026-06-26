package com.dating.mobilegateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// /api/v1/upload/confirm:前端 PUT 完对象存储后回报 objectKey,gateway 转发 user-service Confirm。
@Data
@Schema(description = "头像上传确认入参 —— 客户端直传 OSS 完成后回报 objectKey,触发 statObject 核对 + 落库")
public class ConfirmAvatarReq {

    @NotBlank
    @Schema(description = "presign 步骤返回的 objectKey,原样回传。user-service 据此调 statObject 核实文件已上传,然后写入 custom_avatar 表",
            example = "avatar/12345/202606/8c7a4b2e-...jpg",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String objectKey;
}
