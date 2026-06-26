package com.dating.mobilegateway.controller;

import com.dating.mobilegateway.client.UserProfileClient;
import com.dating.mobilegateway.dto.UpdateProfileReq;
import com.dating.mobilegateway.dto.UpsertOnboardingReq;
import com.dating.mobilegateway.exception.Result;
import com.dating.mobilegateway.vo.UserProfileVO;
import com.dating.youjianxin.proto.user.Gender;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 用户资料 BFF —— 当前仅暴露 onboarding 完善入口。
//   - caller userId 由 JwtAuthFilter 注入 request attr,
//     经 GrpcClientMetadataInterceptor 透传 x-user-id metadata 给 user-service,
//     controller 不手动取
//   - 校验/枚举范围全部交给 user-service,gateway 只做 enum 数值 → proto Gender 的转换
@Slf4j
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
@Tag(name = "Profile", description = "用户资料 / Onboarding")
public class ProfileController {

    private final UserProfileClient userProfileClient;

    @PostMapping("/onboarding")
    @Operation(
            summary = "完善 onboarding 资料 (首次登录后补全昵称 / 性别 / 生日等)",
            description = """
                    入参: UpsertOnboardingReq (所有字段可选,null 不更新)
                      - nickname / birthday / age / height / bio / occupation / education / location
                      - gender (proto Gender 数值,0=UNSPECIFIED / 1=MALE / 2=FEMALE)
                      - defaultAvatarObjectKey (系统预设头像 object_key;自定义上传走 /api/v1/upload/*)
                    出参: UserProfileVO
                      - pending=false 表示 user-service 判定资料齐全,可放行进主流程
                      - avatar / interests 字段按 user-service 当前状态回吐
                    需带 Authorization: Bearer <accessToken>。LoginResultVO.pending=true 时调用。
                    """)
    public Result<UserProfileVO> upsertOnboarding(@Valid @RequestBody UpsertOnboardingReq req) {
        Gender gender = req.getGender() == null ? null : Gender.forNumber(req.getGender());
        return Result.ok(userProfileClient.upsertOnboarding(
                req.getNickname(),
                gender,
                req.getBirthday(),
                req.getAge(),
                req.getHeight(),
                req.getBio(),
                req.getOccupation(),
                req.getEducation(),
                req.getLocation(),
                req.getDefaultAvatarObjectKey()));
    }

    @PatchMapping
    @Operation(
            summary = "更新用户资料 (日常修改昵称 / 简介 / 职业等)",
            description = """
                    入参: UpdateProfileReq (所有字段可选,null 不更新;proto3 optional patch 语义)
                      - nickname / age / height / bio / occupation / education / location
                      - 不可改:gender / birthday (一次性在 onboarding 时定)
                      - 头像:自定义头像走 /api/v1/upload/presign + /confirm;系统预设头像走 /onboarding
                    出参: Boolean
                      - true = user-service 已写库 + 删 user:profile:<id> 缓存
                    需带 Authorization: Bearer <accessToken>;callerUserId 由 JWT 透传 gRPC metadata,
                    user-service 只允许改 caller 自己的资料。
                    """)
    public Result<Boolean> updateProfile(@Valid @RequestBody UpdateProfileReq req) {
        return Result.ok(userProfileClient.updateProfile(
                req.getNickname(),
                req.getAge(),
                req.getHeight(),
                req.getBio(),
                req.getOccupation(),
                req.getEducation(),
                req.getLocation()));
    }
}
