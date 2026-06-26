package com.dating.mobilegateway.converter;

import com.dating.mobilegateway.vo.UserInterestInputVO;
import com.dating.youjianxin.proto.user.AppName;
import com.dating.youjianxin.proto.user.BatchGetProfileRequest;
import com.dating.youjianxin.proto.user.CheckBanRequest;
import com.dating.youjianxin.proto.user.ConfirmAvatarUploadRequest;
import com.dating.youjianxin.proto.user.Gender;
import com.dating.youjianxin.proto.user.GetProfileRequest;
import com.dating.youjianxin.proto.user.Platform;
import com.dating.youjianxin.proto.user.PresignAvatarUploadRequest;
import com.dating.youjianxin.proto.user.ReplaceUserInterestsRequest;
import com.dating.youjianxin.proto.user.ResolveOrCreateByDeviceRequest;
import com.dating.youjianxin.proto.user.ResolveOrCreateByPhoneRequest;
import com.dating.youjianxin.proto.user.ResolveOrCreateByThirdPartyRequest;
import com.dating.youjianxin.proto.user.ThirdPartyPlatform;
import com.dating.youjianxin.proto.user.UpdateProfileRequest;
import com.dating.youjianxin.proto.user.UpsertOnboardingRequest;
import com.dating.youjianxin.proto.user.UserInterestInput;

import java.util.List;

// 手写 proto request Builder ——
//   - MapStruct 不能 target protobuf Builder(红线 6:禁第三方扩展)
//   - 这里集中所有 VO/原参 → proto 入参的构造,避免 client / service 层各拼各的
//   - null 字段跳过 setXxx,避免污染 proto 默认值(尤其 enum / optional)
public final class ProtoReqBuilder {

    private ProtoReqBuilder() {}

    public static ResolveOrCreateByPhoneRequest resolveByPhone(String phoneE164, AppName appName) {
        ResolveOrCreateByPhoneRequest.Builder b = ResolveOrCreateByPhoneRequest.newBuilder();
        if (phoneE164 != null) b.setPhoneE164(phoneE164);
        if (appName != null) b.setAppName(appName);
        return b.build();
    }

    public static ResolveOrCreateByThirdPartyRequest resolveByThirdParty(
            ThirdPartyPlatform platform, String thirdPartyUserId, AppName appName, String googleEmail) {
        ResolveOrCreateByThirdPartyRequest.Builder b = ResolveOrCreateByThirdPartyRequest.newBuilder();
        if (platform != null) b.setPlatform(platform);
        if (thirdPartyUserId != null) b.setThirdPartyUserId(thirdPartyUserId);
        if (appName != null) b.setAppName(appName);
        if (googleEmail != null) b.setGoogleEmail(googleEmail);
        return b.build();
    }

    public static ResolveOrCreateByDeviceRequest resolveByDevice(
            String deviceId, Platform platform, AppName appName) {
        ResolveOrCreateByDeviceRequest.Builder b = ResolveOrCreateByDeviceRequest.newBuilder();
        if (deviceId != null) b.setDeviceId(deviceId);
        if (platform != null) b.setPlatform(platform);
        if (appName != null) b.setAppName(appName);
        return b.build();
    }

    public static CheckBanRequest checkBan(long userId) {
        return CheckBanRequest.newBuilder().setUserId(userId).build();
    }

    public static GetProfileRequest getProfile(long targetUserId) {
        return GetProfileRequest.newBuilder().setTargetUserId(targetUserId).build();
    }

    public static BatchGetProfileRequest batchGetProfile(List<Long> targetUserIds) {
        BatchGetProfileRequest.Builder b = BatchGetProfileRequest.newBuilder();
        if (targetUserIds != null) b.addAllTargetUserIds(targetUserIds);
        return b.build();
    }

    // UpdateProfile 字段全 optional:null/blank 不传(走 proto3 has* 跳过),
    // 显式空串 = 清空(由 user-service 决定)。
    public static UpdateProfileRequest updateProfile(
            String nickname, Integer age, Integer height, String bio,
            String occupation, String education, String location) {
        UpdateProfileRequest.Builder b = UpdateProfileRequest.newBuilder();
        if (nickname != null) b.setNickname(nickname);
        if (age != null) b.setAge(age);
        if (height != null) b.setHeight(height);
        if (bio != null) b.setBio(bio);
        if (occupation != null) b.setOccupation(occupation);
        if (education != null) b.setEducation(education);
        if (location != null) b.setLocation(location);
        return b.build();
    }

    public static UpsertOnboardingRequest upsertOnboarding(
            String nickname, Gender gender, String birthday,
            Integer age, Integer height, String bio,
            String occupation, String education, String location,
            String defaultAvatarObjectKey) {
        UpsertOnboardingRequest.Builder b = UpsertOnboardingRequest.newBuilder();
        if (nickname != null) b.setNickname(nickname);
        if (gender != null) b.setGender(gender);
        if (birthday != null) b.setBirthday(birthday);
        if (age != null) b.setAge(age);
        if (height != null) b.setHeight(height);
        if (bio != null) b.setBio(bio);
        if (occupation != null) b.setOccupation(occupation);
        if (education != null) b.setEducation(education);
        if (location != null) b.setLocation(location);
        if (defaultAvatarObjectKey != null) b.setDefaultAvatarObjectKey(defaultAvatarObjectKey);
        return b.build();
    }

    public static ReplaceUserInterestsRequest replaceUserInterests(List<UserInterestInputVO> interests) {
        ReplaceUserInterestsRequest.Builder b = ReplaceUserInterestsRequest.newBuilder();
        if (interests != null) {
            for (UserInterestInputVO i : interests) {
                UserInterestInput.Builder ib = UserInterestInput.newBuilder();
                if (i.getTabKey() != null) ib.setTabKey(i.getTabKey());
                if (i.getTagKey() != null) ib.setTagKey(i.getTagKey());
                if (i.getPicObjectKey() != null) ib.setPicObjectKey(i.getPicObjectKey());
                b.addInterests(ib.build());
            }
        }
        return b.build();
    }

    public static PresignAvatarUploadRequest presignAvatar(String ext, long expectedSizeBytes) {
        PresignAvatarUploadRequest.Builder b = PresignAvatarUploadRequest.newBuilder();
        if (ext != null) b.setExt(ext);
        b.setExpectedSizeBytes(expectedSizeBytes);
        return b.build();
    }

    public static ConfirmAvatarUploadRequest confirmAvatar(String objectKey) {
        ConfirmAvatarUploadRequest.Builder b = ConfirmAvatarUploadRequest.newBuilder();
        if (objectKey != null) b.setObjectKey(objectKey);
        return b.build();
    }
}
