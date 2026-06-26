package com.dating.im.notification.payload;

import com.dating.im.notification.NotificationKeys;
import com.dating.im.notification.NotificationPayload;

/**
 * "Peer is typing" indicator. Online-push only, not persisted.
 *
 * <p>Pushed under {@code key = "typing"}; the {@code data} JSON sent to the client is:
 * <pre>{@code
 * {
 *   "fromUserId": "1024",   // the user who is typing
 *   "conversationId": "c1", // conversation the typing happens in
 *   "typing": true,         // true = started typing, false = stopped typing
 *   "displaySeconds": 5     // how long the client should show the indicator before auto-hiding;
 *                           //   only meaningful when typing=true, 0 on a stop event
 * }
 * }</pre>
 *
 * @param fromUserId     the user who is typing
 * @param conversationId conversation the typing happens in
 * @param typing         true = started typing, false = stopped typing
 * @param displaySeconds how long the client should show the indicator before auto-hiding;
 *                       only meaningful when {@code typing == true}, 0 for a stop event
 */
public record TypingPayload(String fromUserId, String conversationId, boolean typing, int displaySeconds)
        implements NotificationPayload {

    /** Default seconds the client shows the typing indicator before auto-hiding. */
    public static final int DEFAULT_DISPLAY_SECONDS = 5;

    /** Convenience factory for a "started typing" event with the default display duration. */
    public static TypingPayload start(String fromUserId, String conversationId) {
        return start(fromUserId, conversationId, DEFAULT_DISPLAY_SECONDS);
    }

    /** Convenience factory for a "started typing" event; the client shows the indicator for {@code displaySeconds}. */
    public static TypingPayload start(String fromUserId, String conversationId, int displaySeconds) {
        return new TypingPayload(fromUserId, conversationId, true, displaySeconds);
    }

    /** Convenience factory for a "stopped typing" event. */
    public static TypingPayload stop(String fromUserId, String conversationId) {
        return new TypingPayload(fromUserId, conversationId, false, 0);
    }

    @Override
    public String key() {
        return NotificationKeys.TYPING;
    }
}
