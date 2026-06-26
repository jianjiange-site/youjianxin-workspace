package com.dating.mobilegateway.vo;

import lombok.Data;

// UserInterest proto → VO;pic_key 透传给 App 自拼 URL,picKey 为空表示文字标签。
@Data
public class UserInterestVO {
    private String tabKey;
    private String tagKey;
    private String picKey;
}
