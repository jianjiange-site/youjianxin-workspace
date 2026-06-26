package com.dating.mobilegateway.controller;

import com.dating.mobilegateway.client.UserProfileClient;
import com.dating.mobilegateway.dto.ConfirmAvatarReq;
import com.dating.mobilegateway.dto.PresignAvatarReq;
import com.dating.mobilegateway.exception.Result;
import com.dating.mobilegateway.vo.AvatarVO;
import com.dating.mobilegateway.vo.PresignAvatarUploadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 头像上传两步走的 BFF 代理 ——
//   1) POST /api/v1/upload/presign  → 拿到对象存储 presigned PUT URL + objectKey
//   2) (前端直传对象存储,gateway 不读流)
//   3) POST /api/v1/upload/confirm  → 上传后 user-service statObject 校验并写 custom_avatar
//
// 不在 gateway 读文件流是关键:
//   - 不占 Tomcat IO 线程
//   - 大文件直传对象存储,gateway 只走 metadata
@Slf4j
@RestController
@RequestMapping("/api/v1/upload")
@RequiredArgsConstructor
@Tag(name = "Upload", description = "头像 / 媒体上传 BFF 代理 (实际直传对象存储)")
public class UploadController {

    private final UserProfileClient userProfileClient;

    @PostMapping("/presign")
    @Operation(
            summary = "签发对象存储 PUT presigned URL",
            description = """
                    第 1 步:为客户端签一个 PUT presigned URL,客户端拿到后直传对象存储 (不经网关)。
                    入参: PresignAvatarReq
                      - ext (必填,扩展名 jpg/jpeg/png/webp,白名单外被拒)
                      - expectedSizeBytes (必填,客户端预期字节数;≤10MB)
                    出参: PresignAvatarUploadVO
                      - presignedUrl (有签名 + TTL 的 PUT URL,过期重签)
                      - objectKey (第 3 步 confirm 要原样回传;App 也可拿它自拼公开 URL 展示)
                      - expiresAtMs (签名过期 UTC epoch ms)
                    需带 Authorization: Bearer <accessToken>。
                    """)
    public Result<PresignAvatarUploadVO> presign(@Valid @RequestBody PresignAvatarReq req) {
        return Result.ok(userProfileClient.presignAvatarUpload(req.getExt(), req.getExpectedSizeBytes()));
    }

    @PostMapping("/confirm")
    @Operation(
            summary = "确认上传 (user-service statObject + 落 custom_avatar)",
            description = """
                    第 3 步:客户端完成 PUT 直传后,回报 objectKey,触发服务端核实 + 落库。
                    入参: ConfirmAvatarReq
                      - objectKey (必填,presign 步骤返回的 key 原样回传)
                    出参: AvatarVO
                      - originalKey / minKey / midKey (三档尺寸 object_key;App 自拼公开 URL)
                      - width / height (原图像素,App 预算 placeholder)
                    需带 Authorization: Bearer <accessToken>。user-service 校验:
                      1) statObject 确认对象真实存在 + 大小匹配 expectedSizeBytes
                      2) 写 custom_avatar 表 + 删 user:profile:<id> 缓存
                    """)
    public Result<AvatarVO> confirm(@Valid @RequestBody ConfirmAvatarReq req) {
        return Result.ok(userProfileClient.confirmAvatarUpload(req.getObjectKey()));
    }
}
