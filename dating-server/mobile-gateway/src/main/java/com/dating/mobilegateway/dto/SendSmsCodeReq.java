package com.dating.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

// POST /api/v1/auth/send-sms-code
@Data
public class SendSmsCodeReq {

    // E.164 国际号 ("+8613800000000"),libphonenumber 后续可校验。这里只做基本格式正则。
    @NotBlank
    @Pattern(regexp = "^\\+?[1-9][0-9]{6,14}$", message = "phone must be E.164")
    private String phone;
}
