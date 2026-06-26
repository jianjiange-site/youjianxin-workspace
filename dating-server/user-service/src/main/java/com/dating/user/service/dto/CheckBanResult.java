package com.dating.user.service.dto;

import com.jianjiange.proto.user.BanReason;

// CheckBan 内部出参;ProtoMapper.buildCheckBanResponse 转 proto。
public record CheckBanResult(boolean banned, BanReason reason, long bannedAtMs, String message) {

    public static CheckBanResult notBanned() {
        return new CheckBanResult(false, BanReason.BAN_REASON_UNSPECIFIED, 0L, "");
    }
}
