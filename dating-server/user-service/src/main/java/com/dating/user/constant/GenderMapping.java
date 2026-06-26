package com.dating.user.constant;

import com.dating.youjianxin.proto.user.Gender;

// proto Gender 与 DB user_info.gender SMALLINT 取值完全一致 (0/1/2);
// 仅做空值兜底,无业务转换。
public final class GenderMapping {

    private GenderMapping() {}

    public static Gender toProto(Short db) {
        if (db == null) {
            return Gender.GENDER_UNKNOWN;
        }
        return switch (db) {
            case 1 -> Gender.GENDER_MALE;
            case 2 -> Gender.GENDER_FEMALE;
            default -> Gender.GENDER_UNKNOWN;
        };
    }

    public static Short toDb(Gender proto) {
        if (proto == null) {
            return 0;
        }
        return switch (proto) {
            case GENDER_MALE -> (short) 1;
            case GENDER_FEMALE -> (short) 2;
            default -> (short) 0;
        };
    }
}
