package com.dating.im.model.event;

import com.dating.im.model.ImMessage;

/**
 * A message about to be sent — synchronous pre-hook (e.g. OpenIM {@code callbackBeforeSendSingleMsgCommand}).
 * The handler may allow / reject / modify; today the seam only allows. Acting on reject/modify will
 * require returning a provider-specific response body (see {@code BeforeSendHandler}).
 */
public record MessageBeforeSendEvent(ImMessage message, String callbackCommand) implements ImEvent {

    @Override
    public String provider() {
        return message.provider();
    }
}
