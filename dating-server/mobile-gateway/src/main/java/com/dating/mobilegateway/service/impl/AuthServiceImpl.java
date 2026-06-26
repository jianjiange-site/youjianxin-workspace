package com.dating.mobilegateway.service.impl;

import com.dating.mobilegateway.client.ImClient;
import com.dating.mobilegateway.client.UserIdentityClient;
import com.dating.mobilegateway.constant.JwtClaims;
import com.dating.mobilegateway.dto.LoginDeviceReq;
import com.dating.mobilegateway.dto.LoginPhoneReq;
import com.dating.mobilegateway.dto.LoginThirdPartyReq;
import com.dating.mobilegateway.entity.AuthDevice;
import com.dating.mobilegateway.entity.AuthRefreshToken;
import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.manager.AuthDeviceManager;
import com.dating.mobilegateway.security.IssuedAccessToken;
import com.dating.mobilegateway.security.IssuedRefreshToken;
import com.dating.mobilegateway.security.JwtIssuer;
import com.dating.mobilegateway.security.RefreshTokenManager;
import com.dating.mobilegateway.security.TokenBlacklistManager;
import com.dating.mobilegateway.service.AuthService;
import com.dating.mobilegateway.service.SmsService;
import com.dating.mobilegateway.service.ThirdPartyTokenVerifier;
import com.dating.mobilegateway.vo.CheckBanVO;
import com.dating.mobilegateway.vo.LoginResultVO;
import com.dating.mobilegateway.vo.ResolveOrCreateVO;
import com.dating.mobilegateway.vo.SendSmsCodeVO;
import com.dating.youjianxin.proto.user.AppName;
import com.dating.youjianxin.proto.user.Platform;
import com.dating.youjianxin.proto.user.ThirdPartyPlatform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;

// 登录闭环编排:
//   resolveIdentity(phone/3rd/device)
//     → 在调下游 gRPC 前先把 deviceId 写进 request attr,
//       让 GrpcClientMetadataInterceptor 的 x-device-id 透传起来
//     → UserIdentityClient.resolveOrCreate*
//     → CheckBan 命中 → 403
//     → AuthDevice upsert (touchLastSeen / 新插)
//     → JwtIssuer.issueAccess + issueRefresh
//     → RefreshTokenManager.save (只落 hash)
//     → 返回 LoginResultVO
//
// refresh:RefreshTokenManager.validate (内含 reuse 检测) → CheckBan → 新对 → markUsedAndRotate
// logout:blacklistManager.add(jti) + refreshTokenManager.revokeByUserDevice
//
// AppName 当前只有 APP_NAME_DEFAULT 一个有效值,统一传它,后续多 App 需再加 dto 字段。
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final AppName APP_NAME = AppName.APP_NAME_DEFAULT;

    private final SmsService smsService;
    private final ThirdPartyTokenVerifier thirdPartyVerifier;
    private final UserIdentityClient userIdentityClient;
    private final ImClient imClient;
    private final AuthDeviceManager authDeviceManager;
    private final JwtIssuer jwtIssuer;
    private final RefreshTokenManager refreshTokenManager;
    private final TokenBlacklistManager blacklistManager;

    @Override
    public SendSmsCodeVO sendSmsCode(String phoneE164) {
        return smsService.issue(phoneE164);
    }

    @Override
    public LoginResultVO loginByPhone(LoginPhoneReq req, String clientIp) {
        if (!smsService.verify(req.getPhone(), req.getSmsCode())) {
            throw new BizException(ErrorCodes.SMS_CODE_INVALID, "sms code invalid or expired");
        }
        applyOutboundDeviceId(req.getDeviceId());
        ResolveOrCreateVO resolved = userIdentityClient.resolveByPhone(req.getPhone(), APP_NAME);
        return buildLoginResult(resolved, req.getDeviceId(), platformOrThrow(req.getPlatform()),
                req.getDeviceModel(), req.getOsVersion(), req.getAppVersion(), req.getPushToken(), clientIp);
    }

    @Override
    public LoginResultVO loginByThirdParty(LoginThirdPartyReq req, String clientIp) {
        ThirdPartyPlatform platform = thirdPartyPlatformOrThrow(req.getThirdPartyPlatform());
        ThirdPartyTokenVerifier.ThirdPartyVerifyResult vr =
                thirdPartyVerifier.verify(platform, req.getIdToken(), req.getGoogleEmail());
        applyOutboundDeviceId(req.getDeviceId());
        ResolveOrCreateVO resolved = userIdentityClient.resolveByThirdParty(
                platform, vr.thirdPartyUserId(), APP_NAME, vr.email());
        return buildLoginResult(resolved, req.getDeviceId(), platformOrThrow(req.getPlatform()),
                req.getDeviceModel(), req.getOsVersion(), req.getAppVersion(), req.getPushToken(), clientIp);
    }

    @Override
    public LoginResultVO loginByDevice(LoginDeviceReq req, String clientIp) {
        Platform platform = platformOrThrow(req.getPlatform());
        applyOutboundDeviceId(req.getDeviceId());
        ResolveOrCreateVO resolved =
                userIdentityClient.resolveByDevice(req.getDeviceId(), platform, APP_NAME);
        return buildLoginResult(resolved, req.getDeviceId(), platform,
                req.getDeviceModel(), req.getOsVersion(), req.getAppVersion(), req.getPushToken(), clientIp);
    }

    @Override
    public LoginResultVO refresh(String refreshTokenPlain, String clientIp) {
        AuthRefreshToken row = refreshTokenManager.validate(refreshTokenPlain);
        applyOutboundUser(row.getUserId(), row.getDeviceId());
        // 二次确认 ban 状态 —— 即便登录时未被 ban,refresh 周期可能新增封禁。
        CheckBanVO ban = userIdentityClient.checkBan(row.getUserId());
        if (Boolean.TRUE.equals(ban.getBanned())) {
            refreshTokenManager.revokeByUser(row.getUserId());
            throw new BizException(ErrorCodes.FORBIDDEN,
                    ban.getMessage() != null ? ban.getMessage() : "user banned");
        }
        authDeviceManager.touchLastSeen(findDeviceIdOrNull(row.getUserId(), row.getDeviceId()), clientIp);
        IssuedAccessToken access = jwtIssuer.issueAccess(row.getUserId(), row.getDeviceId());
        IssuedRefreshToken refresh = jwtIssuer.issueRefresh();
        Long newId = refreshTokenManager.save(row.getUserId(), row.getDeviceId(), refresh);
        refreshTokenManager.markUsedAndRotate(row.getId(), newId);
        return new LoginResultVO(
                access.token(),
                refresh.plainToken(),
                row.getUserId(),
                null,
                Boolean.FALSE,
                access.expiresAt().toInstant().toEpochMilli(),
                refresh.expiresAt().toInstant().toEpochMilli());
    }

    @Override
    public void logout(Long userId, String deviceId, String jti, OffsetDateTime accessExpiresAt) {
        if (jti != null && !jti.isBlank() && accessExpiresAt != null) {
            blacklistManager.blacklist(jti, accessExpiresAt);
        }
        if (userId != null && deviceId != null && !deviceId.isBlank()) {
            refreshTokenManager.revokeByUserDevice(userId, deviceId);
        }
    }

    // --- 私有 helpers --------------------------------------------------------

    private LoginResultVO buildLoginResult(
            ResolveOrCreateVO resolved, String deviceId, Platform platform,
            String deviceModel, String osVersion, String appVersion, String pushToken, String clientIp) {
        Long userId = resolved.getUserId();
        // 登录时再确认 ban —— newly_created 也走一遍,代价低
        CheckBanVO ban = userIdentityClient.checkBan(userId);
        if (Boolean.TRUE.equals(ban.getBanned())) {
            throw new BizException(ErrorCodes.FORBIDDEN,
                    ban.getMessage() != null ? ban.getMessage() : "user banned");
        }
        AuthDevice device = new AuthDevice();
        device.setUserId(userId);
        device.setDeviceId(deviceId);
        device.setPlatform((short) platform.getNumber());
        device.setDeviceModel(deviceModel);
        device.setOsVersion(osVersion);
        device.setAppVersion(appVersion);
        device.setPushToken(pushToken);
        device.setLastIp(clientIp);
        authDeviceManager.upsert(device);

        // ban / device 落完才签发,避免被 ban 用户拿到 token。
        applyOutboundUser(userId, deviceId);
        IssuedAccessToken access = jwtIssuer.issueAccess(userId, deviceId);
        IssuedRefreshToken refresh = jwtIssuer.issueRefresh();
        refreshTokenManager.save(userId, deviceId, refresh);

        // 新用户:best-effort 去 OpenIM(经 im-service)建号。失败不阻登录,告警 + getImToken 懒补建兜底。
        // userId 是 user-service 业务主键(BizIdGenerator 生成,env+YYMMDD+seq);OpenIM 要求 nickname 非空,
        // 用 user_{userId} 占位,UpsertOnboarding 后再覆盖。
        if (Boolean.TRUE.equals(resolved.getNewlyCreated())) {
            try {
                imClient.registerImUser(String.valueOf(userId), "user_" + userId, "");
            } catch (Exception e) {
                log.error("IM 建号失败 userId={} (不阻登录, getImToken 懒补建)", userId, e);
            }
        }
        return new LoginResultVO(
                access.token(),
                refresh.plainToken(),
                userId,
                resolved.getPending(),
                resolved.getNewlyCreated(),
                access.expiresAt().toInstant().toEpochMilli(),
                refresh.expiresAt().toInstant().toEpochMilli());
    }

    private Long findDeviceIdOrNull(Long userId, String deviceId) {
        AuthDevice row = authDeviceManager.findByUserAndDevice(userId, deviceId);
        return row == null ? null : row.getId();
    }

    private static Platform platformOrThrow(Integer platform) {
        if (platform == null) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "platform required");
        }
        Platform p = Platform.forNumber(platform);
        if (p == null || p == Platform.PLATFORM_UNSPECIFIED || p == Platform.UNRECOGNIZED) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "platform invalid: " + platform);
        }
        return p;
    }

    private static ThirdPartyPlatform thirdPartyPlatformOrThrow(Integer platform) {
        if (platform == null) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "thirdPartyPlatform required");
        }
        ThirdPartyPlatform p = ThirdPartyPlatform.forNumber(platform);
        if (p == null || p == ThirdPartyPlatform.THIRD_PARTY_PLATFORM_UNSPECIFIED
                || p == ThirdPartyPlatform.UNRECOGNIZED) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT,
                    "thirdPartyPlatform invalid: " + platform);
        }
        return p;
    }

    // 把 deviceId / userId 写进 request attr,让 GrpcClientMetadataInterceptor 透传到下游 x-device-id / x-user-id。
    // 登录前匿名请求没有 JWT,JwtAuthFilter 不会注入,因此这里手动补。
    private static void applyOutboundDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            sra.setAttribute(JwtClaims.REQUEST_ATTR_DEVICE_ID, deviceId, RequestAttributes.SCOPE_REQUEST);
        }
    }

    private static void applyOutboundUser(Long userId, String deviceId) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) {
            return;
        }
        if (userId != null) {
            sra.setAttribute(JwtClaims.REQUEST_ATTR_USER_ID, userId, RequestAttributes.SCOPE_REQUEST);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            sra.setAttribute(JwtClaims.REQUEST_ATTR_DEVICE_ID, deviceId, RequestAttributes.SCOPE_REQUEST);
        }
    }
}
