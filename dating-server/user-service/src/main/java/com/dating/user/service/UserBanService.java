package com.dating.user.service;

import com.dating.user.service.dto.CheckBanResult;

// 封禁判定:DB regulation_status IN (2, 5) + Redis 运营级 set 命中;5min 短缓存。
public interface UserBanService {

    CheckBanResult checkBan(long userId);
}
