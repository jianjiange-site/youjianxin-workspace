package com.dating.mobilegateway.service;

import com.dating.youjianxin.proto.user.ThirdPartyPlatform;

// 三方 token 校验器占位接口。
//   - verify 真实实现需调 Google / Apple / Facebook OAuth verify endpoint
//   - 当 gateway.third-party.enabled=false 时,impl 跳过真校验,thirdPartyUserId 由 token hash 生成
public interface ThirdPartyTokenVerifier {

    ThirdPartyVerifyResult verify(ThirdPartyPlatform platform, String idToken, String googleEmail);

    record ThirdPartyVerifyResult(String thirdPartyUserId, String email) {}
}
