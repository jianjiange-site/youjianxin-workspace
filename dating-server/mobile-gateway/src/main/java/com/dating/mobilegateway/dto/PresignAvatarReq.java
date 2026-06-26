package com.dating.mobilegateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

// /api/v1/upload/presign:gateway 代理 UserProfileService.PresignAvatarUpload。
//   - ext:扩展名 (jpg/jpeg/png/webp),由 user-service 校验白名单
//   - expectedSizeBytes:文件大小,user-service 校验 ≤10MB
@Data
@Schema(description = "头像上传签名入参 —— gateway 代理 user-service 签 PUT presigned URL")
public class PresignAvatarReq {

    @NotBlank
    @Schema(description = "图片扩展名,小写,不带点。仅 jpg / jpeg / png / webp 白名单,其余 user-service 拒",
            example = "jpg", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ext;

    @NotNull
    @Min(1)
    @Schema(description = "客户端预期上传字节数。user-service 校验 ≤10MB,且 confirm 时 statObject 二次核对真实大小",
            example = "256000", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long expectedSizeBytes;
}
