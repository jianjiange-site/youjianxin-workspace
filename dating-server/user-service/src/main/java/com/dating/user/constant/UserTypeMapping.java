package com.dating.user.constant;

import com.dating.youjianxin.proto.user.UserType;

// proto UserType 与 DB user_info.user_type SMALLINT 取值完全一致 (1=DH, 2=BH);
// DB 加了 CHECK (user_type IN (1,2)),0/NULL 写不进库。
// 空值兜底统一回退为 BH(真人),因为登录链路创建 placeholder 默认就是 BH。
public final class UserTypeMapping {

    private UserTypeMapping() {}

    public static UserType toProto(Short db) {
        if (db == null) {
            return UserType.USER_TYPE_BH;
        }
        return switch (db) {
            case 1 -> UserType.USER_TYPE_DH;
            case 2 -> UserType.USER_TYPE_BH;
            default -> UserType.USER_TYPE_BH;
        };
    }

    // proto UNSPECIFIED → null;调用方按需决定是否回填默认值(onboarding 不传 = 不覆盖现有值)
    public static Short toDb(UserType proto) {
        if (proto == null) {
            return null;
        }
        return switch (proto) {
            case USER_TYPE_DH -> (short) 1;
            case USER_TYPE_BH -> (short) 2;
            default -> null;
        };
    }
}
