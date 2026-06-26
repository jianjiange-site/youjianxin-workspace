package com.dating.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

// 用户主表;身份解析后产生 placeholder,onboarding 后补齐资料字段。
// custom_avatar JSONB 先用 String,U7 再上 TypeHandler;condition 在 PG 14+ 非保留字,无需转义。
@Data
@TableName("user_info")
public class UserInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 业务主键:env(1|2)+YYMMDD+当天序号,与物理主键 id 并存;dating-common BizIdGenerator 生成
    private Long userId;

    private String username;
    private String nickname;
    private Short age;
    private String birthday;
    private Short gender;
    private Short height;
    private String bio;
    private String profession;
    private String education;

    private Short appName;
    // 用户类型:1=DH(数字人) 2=BH(真人);DB CHECK 强制非 0,默认 2
    private Short userType;
    private String phoneNumber;
    private String email;
    private String preferredLocation;
    private String zipCode;

    // 居住地(美国):state→city 联动下拉,city_id 引用 geo_city,lat/lng 为城市中心点冗余(Haversine 距离匹配用)
    private String stateCode;
    private String city;
    private Long cityId;
    private Double lat;
    private Double lng;

    // 召回 / 匹配维度(服务于 match-service D0/D1):
    // beautyScore 0-100;DB DEFAULT 50,vision-agent 异步刷新;D1 打分 0.30 权重
    // race 可空(用户未填写);D0 字典序"同人种优先",VARCHAR 不 enum 运营随时改
    private Short beautyScore;
    private String race;

    private Long locale;
    private Short platform;
    private Short condition;

    private String customAvatar;
    private String insId;
    private String insAvatarUrl;

    private Short regulationStatus;
    private Boolean pending;

    private OffsetDateTime lastOpenAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
