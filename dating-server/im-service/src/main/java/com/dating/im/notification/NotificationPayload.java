package com.dating.im.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unifies every "server → client" business notification payload.
 *
 * <p>Each implementation is one notification type: it carries its own {@link #key()} (bound to a
 * {@link NotificationKeys} constant), its persistence semantics ({@link #persist()}) and reliability
 * level, and knows how to serialize itself into the OpenIM {@code data} JSON string. This is where
 * the key/data contract is defined and kept in one place, so callers never hand-assemble JSON.
 *
 * <p>Deliberately a plain interface, not {@code sealed}: on the classpath (unnamed module) a sealed
 * type requires its permitted records in the same package, which would collapse the {@code payload}
 * sub-package; and there is no exhaustive switch over payloads to protect. Drift is guarded by
 * {@link #key()} returning a {@link NotificationKeys} constant plus a unit test asserting key
 * uniqueness.
 */
public interface NotificationPayload {

    /** Notification type; must return a {@link NotificationKeys} constant. */
    String key();

    /**
     * Whether to also persist as a message (into conversation history).
     * Signals (typing / match-success) = {@code false}; system messages (match-welcome) = {@code true}.
     */
    default boolean persist() {
        return false;
    }

    /** 1 = online push only (default), 2 = guaranteed delivery (reconnect/relogin replay). */
    default int reliabilityLevel() {
        return 1;
    }

    /**
     * Serializes this payload into the OpenIM {@code data} JSON string using the injected
     * {@link ObjectMapper} (never {@code new}). Field names become the JSON keys the client reads.
     */
    default String toData(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize notification payload failed: key=" + key(), e);
        }
    }
}
