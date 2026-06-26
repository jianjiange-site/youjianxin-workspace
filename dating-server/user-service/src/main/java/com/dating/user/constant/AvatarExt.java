package com.dating.user.constant;

import java.util.Set;

// 头像上传约束:扩展名白名单 + 最大字节数。
// service 层 (U7) presign 前校验 ext,confirm 时再 statObject 校验实际 size。
public final class AvatarExt {

    private AvatarExt() {}

    public static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "webp");

    public static final long MAX_BYTES = 10L * 1024 * 1024;

    public static boolean isAllowed(String ext) {
        return ext != null && ALLOWED.contains(ext.toLowerCase());
    }
}
