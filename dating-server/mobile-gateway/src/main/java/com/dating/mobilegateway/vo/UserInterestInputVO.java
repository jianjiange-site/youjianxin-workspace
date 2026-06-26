package com.dating.mobilegateway.vo;

import lombok.Data;

// UserInterestInput 入参 VO(REST → ReplaceUserInterests)。
//   - picObjectKey 非空 = 图片标签的对象存储 object_key(由 PresignAvatarUpload 出);空 = 文字标签
@Data
public class UserInterestInputVO {
    private String tabKey;
    private String tagKey;
    private String picObjectKey;
}
