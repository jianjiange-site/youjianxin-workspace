package com.dating.im.notification.payload;

import com.dating.im.notification.NotificationKeys;
import com.dating.im.notification.NotificationPayload;

/**
 * Match welcome system message ("你们配对了"). Persisted into the conversation history.
 *
 * <p>Distinct from {@code MatchSuccessPayload}: that is a transient online signal; this is the first
 * system message that must stay in chat history, hence {@link #persist()} = {@code true}.
 *
 * @param matchId        match id
 * @param conversationId conversation to land the system message in (optional)
 * @param text           welcome text; caller passes the resolved copy (e.g. "你们配对了")
 */
public record MatchWelcomePayload(String matchId, String conversationId, String text)
        implements NotificationPayload {

    @Override
    public String key() {
        return NotificationKeys.MATCH_WELCOME;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public int reliabilityLevel() {
        return 2;
    }
}
