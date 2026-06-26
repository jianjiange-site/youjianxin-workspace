package com.dating.im.model.event;

import com.dating.im.model.ImMessage;

/** A message that has already been sent (e.g. OpenIM {@code callbackAfterSendSingleMsgCommand}). */
public record MessageSentEvent(ImMessage message, String callbackCommand) implements ImEvent {

    @Override
    public String provider() {
        return message.provider();
    }
}
