package com.dating.common.alert.throttle;

import com.dating.common.alert.AlertEvent;

// 异常签名 = hash(异常 class FQN, top frame class#method:line)。
// null throwable 退化为 scene + level 的 hash。
// 64-bit splitmix64 风格混合 → 直接做 ConcurrentHashMap key,省掉长字符串 key 比较。
public final class ExceptionSignature {

    private ExceptionSignature() {}

    public static long of(AlertEvent e) {
        Throwable t = e.throwable();
        if (t == null) {
            return mix64(e.scene().hashCode(), e.level().ordinal());
        }
        int classHash = t.getClass().getName().hashCode();
        int frameHash = topFrameHash(t);
        return mix64(classHash, frameHash);
    }

    public static String topFrameOf(Throwable t) {
        if (t == null) return "noframe";
        StackTraceElement[] st = t.getStackTrace();
        if (st == null || st.length == 0) return "noframe";
        StackTraceElement f = st[0];
        return f.getClassName() + "#" + f.getMethodName() + ":" + f.getLineNumber();
    }

    private static int topFrameHash(Throwable t) {
        return topFrameOf(t).hashCode();
    }

    private static long mix64(int a, int b) {
        long h = ((long) a << 32) | (b & 0xffffffffL);
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return h ^ (h >>> 31);
    }
}
