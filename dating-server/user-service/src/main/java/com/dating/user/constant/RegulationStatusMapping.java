package com.dating.user.constant;

import com.dating.youjianxin.proto.user.RegulationStatus;

// proto RegulationStatus 与 DB user_info.regulation_status SMALLINT 取值差 1:
//   DB 0=Active 1=KGroup 2=Banned 3=Admin 4=Collaborator 5=Suspended 6=Reported
//   proto ACTIVE=1 K_GROUP=2 BANNED=3 ADMIN=4 COLLABORATOR=5 SUSPENDED=6 REPORTED=7
// 封禁判定固定为 DB regulation_status IN (2, 5) → 命中 Banned 或 Suspended。
public final class RegulationStatusMapping {

    private RegulationStatusMapping() {}

    public static RegulationStatus toProto(Short db) {
        if (db == null) {
            return RegulationStatus.REGULATION_STATUS_UNSPECIFIED;
        }
        return switch (db) {
            case 0 -> RegulationStatus.REGULATION_STATUS_ACTIVE;
            case 1 -> RegulationStatus.REGULATION_STATUS_K_GROUP;
            case 2 -> RegulationStatus.REGULATION_STATUS_BANNED;
            case 3 -> RegulationStatus.REGULATION_STATUS_ADMIN;
            case 4 -> RegulationStatus.REGULATION_STATUS_COLLABORATOR;
            case 5 -> RegulationStatus.REGULATION_STATUS_SUSPENDED;
            case 6 -> RegulationStatus.REGULATION_STATUS_REPORTED;
            default -> RegulationStatus.REGULATION_STATUS_UNSPECIFIED;
        };
    }

    // 封禁判定:DB regulation_status IN (2, 5)
    public static boolean isBanned(Short db) {
        return db != null && (db == 2 || db == 5);
    }

    public static boolean isBannedHard(Short db) {
        return db != null && db == 2;
    }

    public static boolean isSuspended(Short db) {
        return db != null && db == 5;
    }
}
