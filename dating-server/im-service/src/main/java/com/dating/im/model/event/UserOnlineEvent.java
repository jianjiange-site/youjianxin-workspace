package com.dating.im.model.event;

/** A user came online (e.g. OpenIM {@code callbackUserOnlineCommand}). */
public record UserOnlineEvent(String provider,
                              String callbackCommand,
                              String userId,
                              String platform,
                              long timestamp) implements ImEvent {
}
