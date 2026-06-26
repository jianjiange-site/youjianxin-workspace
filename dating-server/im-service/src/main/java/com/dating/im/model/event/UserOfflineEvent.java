package com.dating.im.model.event;

/** A user went offline (e.g. OpenIM {@code callbackUserOfflineCommand}). */
public record UserOfflineEvent(String provider,
                               String callbackCommand,
                               String userId,
                               String platform,
                               long timestamp) implements ImEvent {
}
