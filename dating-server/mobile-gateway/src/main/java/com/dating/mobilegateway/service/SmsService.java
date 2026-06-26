package com.dating.mobilegateway.service;

import com.dating.mobilegateway.vo.SendSmsCodeVO;

// 手机短信验证码占位接口。
//   - issue(phone):生成 / 复用 code,落 Redis,返回前端展示用的 mockCode (生产为 null)
//   - verify(phone, code):成功 = true 并删除 key (一次性)
//
// 真实接入由具体 impl 在 sendUpstream(...) 实现,Nacos 开关 gateway.sms.enabled=false 时跳过真发。
public interface SmsService {

    SendSmsCodeVO issue(String phoneE164);

    boolean verify(String phoneE164, String code);
}
