package com.dating.mobilegateway.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// /auth/send-sms-code 出参:仅暴露下次可发的冷却秒数,debug profile 才回吐验证码(mock 模式)。
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendSmsCodeVO {
    private Integer cooldownSeconds;
    private String mockCode;
}
