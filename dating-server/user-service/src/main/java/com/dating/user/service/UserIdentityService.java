package com.dating.user.service;

import com.dating.user.service.dto.ResolveOrCreateResult;

// 身份解析:已通过短信/三方授权/设备 ID 一次验证后的 identifier → user_id。
// 实现按 (identifier, app_name) 在对应绑定表查找;命中刷 last_open_at,未命中开事务创建 placeholder + 绑定。
// 注意:此处三个入口都是真人(App 端登录),user_type 走 DB DEFAULT 2(BH)。
// DH 数字人不走这条路径,由 admin/后台脚本灌入或 UpsertOnboarding(user_type=USER_TYPE_DH) 改写。
public interface UserIdentityService {

    ResolveOrCreateResult resolveByPhone(String phoneRaw, Short appName);

    ResolveOrCreateResult resolveByThirdParty(Short platform, String thirdPartyUserId, Short appName, String googleEmail);

    ResolveOrCreateResult resolveByDevice(String deviceId, Short platform, Short appName);
}
