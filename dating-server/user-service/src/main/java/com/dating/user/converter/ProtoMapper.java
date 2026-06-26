package com.dating.user.converter;

import com.dating.user.constant.GenderMapping;
import com.dating.user.constant.RegulationStatusMapping;
import com.dating.user.constant.UserTypeMapping;
import com.dating.user.vo.UserInterestVO;
import com.dating.user.vo.UserProfileVO;
import com.jianjiange.proto.user.Avatar;
import com.jianjiange.proto.user.BanReason;
import com.jianjiange.proto.user.CheckBanResponse;
import com.jianjiange.proto.user.ResolveOrCreateResponse;
import com.jianjiange.proto.user.UserInterest;
import com.jianjiange.proto.user.UserProfile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

// 手写 proto builder。MapStruct 不能 target protobuf builder;红线 6 不引第三方扩展。
// 反向 (proto request → VO) 不集中:proto optional 字段需要 hasXxx() 动态判断,
// 集中转换会丢失 has 语义,故 service 层就近拆 getter。
//
// 注意:Avatar / UserInterest proto 现在并存 *_url(历史)与 *_key(0.4.0 新增):
// - Avatar 同时设 setOriginalUrl + setOriginalKey,App 端读 *_key 自拼 CDN URL
// - UserInterest pic_url 字段位置承载 pic_key 值(单字段沿用 *_url)
// 后续 proto 升 1.0 时彻底 drop *_url,届时再清理。
@Component
public class ProtoMapper {

    public UserProfile toUserProfileProto(UserProfileVO vo) {
        if (vo == null) {
            return UserProfile.getDefaultInstance();
        }
        UserProfile.Builder b = UserProfile.newBuilder()
                .setUserId(vo.getUserId() == null ? 0L : vo.getUserId())
                .setNickname(nullToEmpty(vo.getNickname()))
                .setAge(vo.getAge() == null ? 0 : vo.getAge())
                .setGender(GenderMapping.toProto(vo.getGender()))
                .setHeight(vo.getHeight() == null ? 0 : vo.getHeight())
                .setBio(nullToEmpty(vo.getBio()))
                .setOccupation(nullToEmpty(vo.getOccupation()))
                .setEducation(nullToEmpty(vo.getEducation()))
                .setLocation(nullToEmpty(vo.getLocation()))
                .setBirthday(nullToEmpty(vo.getBirthday()))
                .setRegulationStatus(RegulationStatusMapping.toProto(vo.getRegulationStatus()))
                .setPending(Boolean.TRUE.equals(vo.getPending()))
                .setLastOpenAtMs(vo.getLastOpenAt() == null
                        ? 0L
                        : vo.getLastOpenAt().toInstant().toEpochMilli())
                .setUserType(UserTypeMapping.toProto(vo.getUserType()))
                // 召回 / 匹配字段(0.4.0 proto 扩展;服务于 match-service D0/D1)
                .setCreatedAtMs(vo.getCreatedAt() == null
                        ? 0L
                        : vo.getCreatedAt().toInstant().toEpochMilli())
                .setCityId(vo.getCityId() == null ? 0L : vo.getCityId())
                .setStateCode(nullToEmpty(vo.getStateCode()))
                .setCity(nullToEmpty(vo.getCity()))
                .setLat(vo.getLat() == null ? 0d : vo.getLat())
                .setLng(vo.getLng() == null ? 0d : vo.getLng())
                .setBeautyScore(vo.getBeautyScore() == null ? 0 : vo.getBeautyScore())
                .setRace(nullToEmpty(vo.getRace()));

        if (vo.getAvatar() != null) {
            b.setAvatar(toAvatarProto(vo.getAvatar()));
        }
        if (vo.getInterests() != null) {
            for (UserInterestVO i : vo.getInterests()) {
                if (i != null) {
                    b.addInterests(toUserInterestProto(i));
                }
            }
        }
        return b.build();
    }

    public Avatar toAvatarProto(UserProfileVO.AvatarVO avatar) {
        if (avatar == null) {
            return Avatar.getDefaultInstance();
        }
        // *_url 与 *_key 同时填充(都用 object_key 值);App 端优先读 *_key
        return Avatar.newBuilder()
                .setOriginalUrl(nullToEmpty(avatar.getOriginalKey()))
                .setMinUrl(nullToEmpty(avatar.getMinKey()))
                .setMidUrl(nullToEmpty(avatar.getMidKey()))
                .setOriginalKey(nullToEmpty(avatar.getOriginalKey()))
                .setMinKey(nullToEmpty(avatar.getMinKey()))
                .setMidKey(nullToEmpty(avatar.getMidKey()))
                .setWidth(avatar.getWidth() == null ? 0 : avatar.getWidth())
                .setHeight(avatar.getHeight() == null ? 0 : avatar.getHeight())
                .build();
    }

    public UserInterest toUserInterestProto(UserInterestVO vo) {
        if (vo == null) {
            return UserInterest.getDefaultInstance();
        }
        // 同上,pic_url 字段位置承载 pic_key 值。
        return UserInterest.newBuilder()
                .setTabKey(nullToEmpty(vo.getTabKey()))
                .setTagKey(nullToEmpty(vo.getTagKey()))
                .setPicUrl(nullToEmpty(vo.getPicKey()))
                .build();
    }

    public List<UserProfile> toUserProfileProtoList(List<UserProfileVO> vos) {
        if (vos == null) {
            return List.of();
        }
        return vos.stream().filter(Objects::nonNull).map(this::toUserProfileProto).toList();
    }

    public ResolveOrCreateResponse buildResolveOrCreateResponse(long userId, boolean pending, boolean newlyCreated) {
        return ResolveOrCreateResponse.newBuilder()
                .setUserId(userId)
                .setPending(pending)
                .setNewlyCreated(newlyCreated)
                .build();
    }

    public CheckBanResponse buildCheckBanResponse(boolean banned, BanReason reason, long bannedAtMs, String message) {
        return CheckBanResponse.newBuilder()
                .setBanned(banned)
                .setReason(reason == null ? BanReason.BAN_REASON_UNSPECIFIED : reason)
                .setBannedAtMs(bannedAtMs)
                .setMessage(nullToEmpty(message))
                .build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
