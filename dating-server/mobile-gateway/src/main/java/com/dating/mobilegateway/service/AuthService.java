package com.dating.mobilegateway.service;

import com.dating.mobilegateway.dto.LoginDeviceReq;
import com.dating.mobilegateway.dto.LoginPhoneReq;
import com.dating.mobilegateway.dto.LoginThirdPartyReq;
import com.dating.mobilegateway.vo.LoginResultVO;
import com.dating.mobilegateway.vo.SendSmsCodeVO;

// gateway 鉴权域核心编排:
//   1. 解析身份(SMS / 第三方 / 设备)→ 调 UserIdentityClient.ResolveOrCreate 拿 userId
//   2. CheckBan 拦截封禁账户
//   3. AuthDevice upsert
//   4. JWT 签发 (access + refresh) 落库
//   5. 返回 LoginResultVO
//
// refresh / logout 走相同 service:
//   - refresh:校验旧 token (含 reuse 检测) → 新对 → markUsedAndRotate
//   - logout:把当前 access 的 jti 拉黑 + 撤销当前用户当前设备所有 refresh
public interface AuthService {

    SendSmsCodeVO sendSmsCode(String phoneE164);

    LoginResultVO loginByPhone(LoginPhoneReq req, String clientIp);

    LoginResultVO loginByThirdParty(LoginThirdPartyReq req, String clientIp);

    LoginResultVO loginByDevice(LoginDeviceReq req, String clientIp);

    LoginResultVO refresh(String refreshTokenPlain, String clientIp);

    void logout(Long userId, String deviceId, String jti, java.time.OffsetDateTime accessExpiresAt);
}
