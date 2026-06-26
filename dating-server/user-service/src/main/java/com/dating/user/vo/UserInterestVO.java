package com.dating.user.vo;

import lombok.Data;

// 内部 VO,与 proto user.UserInterest 对齐。
// pic_key 为对象存储 object_key,文字标签 picKey 留空;
// 不在服务端签 URL —— App 侧拿到 key 后自拼 ${cdnBaseUrl}/${bucket}/${key}。
@Data
public class UserInterestVO {

    private String tabKey;
    private String tagKey;
    // object_key;空 = 文字标签
    private String picKey;
}
