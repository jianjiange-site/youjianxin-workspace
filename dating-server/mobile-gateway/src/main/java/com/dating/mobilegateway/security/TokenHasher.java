package com.dating.mobilegateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// Refresh token 明文 → SHA-256 hex 小写。明文绝不入库,DB 仅存 hash;
// 入库前 / 校验时都过这里,保证算法一致(避免 G2 / G3 / G5 各算各的)。
public final class TokenHasher {

    private TokenHasher() {}

    public static String sha256Hex(String plain) {
        if (plain == null || plain.isEmpty()) {
            throw new IllegalArgumentException("token hash input must not be empty");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            return toHexLower(digest);
        } catch (NoSuchAlgorithmException e) {
            // JDK ships SHA-256 —— 拿不到只能 fail-fast。
            throw new IllegalStateException("SHA-256 not available in this JVM", e);
        }
    }

    private static String toHexLower(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            hex[i * 2] = HEX[v >>> 4];
            hex[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(hex);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
}
