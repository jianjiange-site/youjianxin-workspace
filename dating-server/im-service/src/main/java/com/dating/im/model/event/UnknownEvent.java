package com.dating.im.model.event;

/**
 * A callback whose command this service does not (yet) model. Logged + acked so the provider stops
 * retrying. This is distinct from "a modeled variant we forgot to handle" — the sealed {@link ImEvent}
 * switch guards that at compile time, so this variant must never become a catch-all for known types.
 */
public record UnknownEvent(String provider, String callbackCommand, String raw) implements ImEvent {
}
