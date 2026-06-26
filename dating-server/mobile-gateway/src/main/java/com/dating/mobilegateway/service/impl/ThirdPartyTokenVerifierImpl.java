package com.dating.mobilegateway.service.impl;

import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.security.TokenHasher;
import com.dating.mobilegateway.service.ThirdPartyTokenVerifier;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.dating.youjianxin.proto.user.ThirdPartyPlatform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;

// 三方 token 校验:
//   - gateway.third-party.enabled=false (默认):本地 mock,thirdPartyUserId = sha256(token).substring(0,32)
//     避免不同 token mock 出同 userId;同 token 反复登录又能拿到同 userId,保证测试可重入
//   - gateway.third-party.enabled=true:按 platform 分流真校验
//     - Google:GoogleIdTokenVerifier (验签 + iss + aud + exp);取 payload.sub 作 thirdPartyUserId
//     - Apple / Facebook:暂未接入,直接拒
@Slf4j
@Service
public class ThirdPartyTokenVerifierImpl implements ThirdPartyTokenVerifier {

    private final boolean thirdPartyEnabled;
    private final ObjectProvider<GoogleIdTokenVerifier> googleVerifierProvider;

    public ThirdPartyTokenVerifierImpl(
            @Value("${gateway.third-party.enabled:false}") boolean thirdPartyEnabled,
            ObjectProvider<GoogleIdTokenVerifier> googleVerifierProvider) {
        this.thirdPartyEnabled = thirdPartyEnabled;
        this.googleVerifierProvider = googleVerifierProvider;
    }

    @Override
    public ThirdPartyVerifyResult verify(ThirdPartyPlatform platform, String idToken, String googleEmail) {
        if (platform == null || platform == ThirdPartyPlatform.THIRD_PARTY_PLATFORM_UNSPECIFIED
                || platform == ThirdPartyPlatform.UNRECOGNIZED) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "thirdPartyPlatform required");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new BizException(ErrorCodes.THIRD_PARTY_TOKEN_INVALID, "idToken required");
        }
        if (!thirdPartyEnabled) {
            String stable = TokenHasher.sha256Hex(idToken).substring(0, 32);
            log.info("[mock-third-party] platform={} mockUserId={}", platform, stable);
            return new ThirdPartyVerifyResult(stable,
                    platform == ThirdPartyPlatform.THIRD_PARTY_PLATFORM_GOOGLE ? googleEmail : null);
        }
        return switch (platform) {
            case THIRD_PARTY_PLATFORM_GOOGLE -> verifyGoogle(idToken, googleEmail);
            default -> throw new BizException(ErrorCodes.THIRD_PARTY_TOKEN_INVALID,
                    "third-party platform " + platform + " not implemented yet");
        };
    }

    // Google idToken 校验:GoogleIdTokenVerifier 一次性校验签名 (拉 JWKS) + iss + aud + exp。
    //   - sub:Google 用户稳定唯一 ID,作为 thirdPartyUserId
    //   - email:优先取 token.payload.email (用户授予 email scope 时由 Google 签发);否则 fallback 入参 googleEmail
    //   - 校验失败 / 网络异常一律映射为 THIRD_PARTY_TOKEN_INVALID,不把细节回给客户端 (防探测)
    private ThirdPartyVerifyResult verifyGoogle(String idToken, String googleEmail) {
        GoogleIdTokenVerifier verifier = googleVerifierProvider.getIfAvailable();
        if (verifier == null) {
            log.error("google idToken verifier bean not configured; check gateway.third-party.google.client-ids");
            throw new BizException(ErrorCodes.THIRD_PARTY_TOKEN_INVALID, "google verifier not configured");
        }
        try {
            GoogleIdToken token = verifier.verify(idToken);
            if (token == null) {
                // verify() 返回 null = 签名 / iss / aud / exp 任一不过
                throw new BizException(ErrorCodes.THIRD_PARTY_TOKEN_INVALID, "google idToken invalid");
            }
            GoogleIdToken.Payload payload = token.getPayload();
            String sub = payload.getSubject();
            if (sub == null || sub.isBlank()) {
                throw new BizException(ErrorCodes.THIRD_PARTY_TOKEN_INVALID, "google idToken missing sub");
            }
            String email = payload.getEmail() != null && !payload.getEmail().isBlank()
                    ? payload.getEmail()
                    : googleEmail;
            log.info("[google-third-party] sub={} aud={} emailFromToken={}",
                    sub, payload.getAudience(), payload.getEmail() != null);
            return new ThirdPartyVerifyResult(sub, email);
        } catch (GeneralSecurityException | IOException e) {
            log.warn("google idToken verify error", e);
            throw new BizException(ErrorCodes.THIRD_PARTY_TOKEN_INVALID, "google idToken verify failed");
        }
    }
}
