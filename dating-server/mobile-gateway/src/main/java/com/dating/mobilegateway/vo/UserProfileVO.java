package com.dating.mobilegateway.vo;

import lombok.Data;

import java.util.List;

// user-service UserProfile proto → gateway 出参 VO。
// 字段含义对齐 proto;时间戳 long(ms),enum 转 int(0/1/2 ...)。
@Data
public class UserProfileVO {

    private Long userId;
    private String nickname;
    private Integer age;
    private Integer gender;          // 0/1/2,见 user.proto Gender
    private Integer height;
    private String bio;
    private String occupation;
    private String education;
    private String location;
    private String birthday;

    private AvatarVO avatar;
    private List<UserInterestVO> interests;

    private Boolean pending;
    private Integer regulationStatus;
    private Long lastOpenAtMs;
}
