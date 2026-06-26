package com.dating.user.vo;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

// 内部 VO:proto user.UserProfile 的 Java 镜像。
// UI 命名对齐 (occupation=DB profession, location=DB preferred_location);
// gender / regulationStatus 保留 DB SMALLINT 值,ProtoMapper 再转 proto 枚举。
//
// avatar / interests 只携带 object_key,App 侧自拼 ${cdnBaseUrl}/${bucket}/${key};
// 服务端不再为公开资产签 GET URL(头像走 CDN public)。
@Data
public class UserProfileVO {

    private Long userId;
    private String nickname;
    private Integer age;
    private Short gender;
    private Integer height;
    private String bio;
    private String occupation;
    private String education;
    private String location;
    private String birthday;

    private Short regulationStatus;
    private Boolean pending;
    private OffsetDateTime lastOpenAt;

    // 用户类型 DB 原值:1=DH(数字人) 2=BH(真人);ProtoMapper 转 proto UserType 枚举
    private Short userType;

    // 召回 / 匹配维度(服务于 match-service D0/D1):
    // beautyScore 0-100;race 可空;createdAt 用于 new_bh_bonus 判定;
    // 居住地 cityId / stateCode / city / lat / lng 用于 GEO 召回(Phase 1 用 city_id 同城)
    private Integer beautyScore;
    private String race;
    private OffsetDateTime createdAt;
    private Long cityId;
    private String stateCode;
    private String city;
    private Double lat;
    private Double lng;

    // service 层解析 custom_avatar JSONB 提取 object_key 后塞这里;无头像则保持 null。
    private AvatarVO avatar;

    // 兴趣列表;pic_key 由 App 侧自拼 URL,文字标签 picKey 为空。
    private List<UserInterestVO> interests;

    @Data
    public static class AvatarVO {
        private String originalKey;
        private String minKey;
        private String midKey;
        private Integer width;
        private Integer height;
    }
}
