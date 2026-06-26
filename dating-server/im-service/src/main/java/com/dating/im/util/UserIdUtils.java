package com.dating.im.util;

/**
 * Helpers for OpenIM user-id strings. OpenIM userIds are numeric strings (the platform user id);
 * non-numeric / empty values are treated as "unknown" and yield {@code null}.
 */
public final class UserIdUtils {

    private UserIdUtils() {
    }

    /** Parses an OpenIM userId to {@code Long}, or {@code null} if blank / non-numeric. */
    public static Long parseLong(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
