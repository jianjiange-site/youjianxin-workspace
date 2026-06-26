package com.dating.im.notification;

/**
 * Well-known business notification {@code key} values used with {@link NotificationService}.
 *
 * <p>{@code key} is an open string contract shared across services (OpenIM-native): clients switch
 * on it to interpret the {@code data} payload. This class is the single source of truth for the keys
 * im-service defines; each key has a matching {@link NotificationPayload} record under
 * {@code com.dating.im.notification.payload} that models its {@code data} structure.
 */
public final class NotificationKeys {

    /** "Peer is typing" indicator. See {@code TypingPayload}. Online-push only. */
    public static final String TYPING = "typing";

    /** Swipe-card match succeeded — online signal, not persisted. See {@code MatchSuccessPayload}. */
    public static final String MATCH_SUCCESS = "match_success";

    /** Match welcome system message, persisted into the conversation. See {@code MatchWelcomePayload}. */
    public static final String MATCH_WELCOME = "match_welcome";

    private NotificationKeys() {
    }
}
