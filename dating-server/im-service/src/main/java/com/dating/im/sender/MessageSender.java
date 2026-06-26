package com.dating.im.sender;

import com.dating.im.model.ImMessage;

/** Sends vendor-agnostic {@link ImMessage} through a specific IM provider. */
public interface MessageSender {

    /** Returns {@code true} if this sender handles the given provider. */
    boolean supports(String provider);

    /** Sends a message to the IM platform. Returns {@code true} on success. */
    boolean send(ImMessage msg);
}
