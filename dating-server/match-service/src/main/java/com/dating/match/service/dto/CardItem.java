package com.dating.match.service.dto;

import com.dating.youjianxin.proto.user.UserProfile;
import com.dating.youjianxin.proto.user.UserType;

import java.util.List;

/**
 * Feed 卡片(service 层中间 VO,grpc 层再转 proto Card)。
 *
 * <p>photo_keys 字段优先取 Avatar.original_key,fallback Avatar.original_url(同值 object_key,见 user.proto)。
 *
 * <p>stateCode / city 是给 App 展示的居住地:
 * <ul>
 *   <li>BH(真人):取自 user_info.state_code / city(用户 onboarding 时选)</li>
 *   <li>DH(数字人):withProfile 阶段先留空,FeedService 拿到 caller 维度的
 *       {@code pickDhCitiesForCaller} 覆盖值后再用 {@link #withDhCityOverride(String, String)} 覆盖</li>
 * </ul>
 */
public record CardItem(long targetUserId,
                       short targetUserTypeDb,
                       String nickname,
                       int age,
                       List<String> photoKeys,
                       String bio,
                       double distanceKm,
                       String stateCode,
                       String city) {

    public CardItem(long targetUserId, short targetUserTypeDb) {
        this(targetUserId, targetUserTypeDb, "", 0, List.of(), "", -1d, "", "");
    }

    public CardItem withProfile(UserProfile p) {
        if (p == null) return this;
        String key = p.hasAvatar()
                ? (p.getAvatar().getOriginalKey().isEmpty()
                        ? p.getAvatar().getOriginalUrl()
                        : p.getAvatar().getOriginalKey())
                : "";
        List<String> photoKeys = key.isEmpty() ? List.of() : List.of(key);
        boolean isDH = p.getUserType() == UserType.USER_TYPE_DH;
        double dist = isDH ? -1d : 0d;
        // DH 的 state/city 留空,由后续 pickDhCitiesForCaller 覆盖;BH 直接用真值
        String stateCode = isDH ? "" : p.getStateCode();
        String city = isDH ? "" : p.getCity();
        return new CardItem(p.getUserId(),
                isDH ? (short) 2 : (short) 1,
                p.getNickname(), p.getAge(), photoKeys, p.getBio(), dist,
                stateCode, city);
    }

    /**
     * 给 DH 卡片应用 caller 维度的位置覆盖(同 state 不同 city)。
     * 非 DH 卡片调用本方法是 no-op。
     */
    public CardItem withDhCityOverride(String stateCode, String city) {
        if (targetUserTypeDb != 2) return this;
        return new CardItem(targetUserId, targetUserTypeDb,
                nickname, age, photoKeys, bio, distanceKm,
                stateCode, city);
    }
}
