package com.dating.mobilegateway.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// 登录 / refresh 统一出参。
//   - accessToken / refreshToken:明文 (后者仅此一次返回,DB 只存 SHA-256 hash)
//   - userId:外露的用户主键 (前端用作展示 / 缓存)
//   - pending:user-service ResolveOrCreate 返的 onboarding flag (true = 需要补完 UpsertOnboarding)
//   - newlyCreated:首次注册标识,前端可决定是否走引导流程
//   - accessExpiresAtMs / refreshExpiresAtMs:UTC 毫秒,前端做兜底主动 refresh
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录 / 刷新统一出参 (login-phone / login-third-party / login-device / refresh 共用)")
public class LoginResultVO {

    @Schema(description = "JWT access token。后续业务请求放 Authorization: Bearer <accessToken>")
    private String accessToken;

    @Schema(description = "refresh token 明文。仅本次响应返回一次,DB 只存 SHA-256 hash;丢了只能让用户重登")
    private String refreshToken;

    @Schema(description = "用户主键 (雪花 ID)。前端可用于本地缓存 / 展示 key", example = "1789234567890123456")
    private Long userId;

    @Schema(description = "true = 资料未完成 onboarding,前端需引导用户走 UpsertOnboarding 补完资料")
    private Boolean pending;

    @Schema(description = "true = 本次登录触发了首次注册,前端可决定是否走新用户引导流程")
    private Boolean newlyCreated;

    @Schema(description = "access token 过期时刻,UTC 毫秒 (epoch ms)。前端用于在过期前主动 refresh",
            example = "1748908800000")
    private Long accessExpiresAtMs;

    @Schema(description = "refresh token 过期时刻,UTC 毫秒 (epoch ms)。过期后必须重新登录",
            example = "1751500800000")
    private Long refreshExpiresAtMs;
}
