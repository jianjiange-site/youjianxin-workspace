package com.dating.mobilegateway.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// /im/token 出参:OpenIM WS 直连所需的 userId + imToken。
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "IM token 出参 —— App 用 OpenIM SDK 直连 WS(10001) 所需凭据")
public class ImTokenVO {

    @Schema(description = "OpenIM 用户 ID(与业务 userId 一致)", example = "12345")
    private String userId;

    @Schema(description = "OpenIM WS 连接 token")
    private String imToken;
}
