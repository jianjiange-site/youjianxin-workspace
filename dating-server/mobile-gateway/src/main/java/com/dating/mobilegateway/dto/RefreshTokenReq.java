package com.dating.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// POST /api/v1/auth/refresh:用未过期未撤销的 refresh 换新 access+refresh 对,
// 旧 refresh 立即 markUsedAndRotate -> rotated_to_id 链到新 refresh id (供 token reuse 检测)。
@Data
public class RefreshTokenReq {

    @NotBlank
    private String refreshToken;
}
