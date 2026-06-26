package com.dating.mobilegateway.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// /home/card 出参:聚合多个 client 的结果,目前只含 UserProfile;后续 relation / im 在此扩展。
//
// 设计:
//   - selfUserId:发起方 (来自 JwtAuthFilter 注入)
//   - target:被查看的用户档案 (调 UserProfileClient.getProfile)
//   - relation 等下游对接后再补字段,先留空对象
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HomeCardVO {

    private Long selfUserId;
    private UserProfileVO target;
}
