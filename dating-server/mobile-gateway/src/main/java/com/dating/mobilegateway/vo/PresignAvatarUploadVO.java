package com.dating.mobilegateway.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// PresignAvatarUpload 出参 → VO,直接透传给客户端 PUT 对象存储。
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "头像上传签名出参 —— 客户端拿 presignedUrl 直接 PUT 二进制到对象存储,不再经网关")
public class PresignAvatarUploadVO {

    @Schema(description = "对象存储 presigned PUT URL。带签名,过期后失效,只能 PUT 不能 GET",
            example = "https://e2.idrivee2-XX.com/dating-user/avatar/.../xxx.jpg?X-Amz-...")
    private String presignedUrl;

    @Schema(description = "对象 key。confirm 步骤要原样回传;App 自拼 ${cdnBaseUrl}/${bucket}/${objectKey} 展示",
            example = "avatar/12345/202606/8c7a4b2e-...jpg")
    private String objectKey;

    @Schema(description = "签名过期时刻,UTC 毫秒 (epoch ms)。过期后 PUT 会被对象存储拒,需要重新调 presign",
            example = "1748908800000")
    private Long expiresAtMs;
}
