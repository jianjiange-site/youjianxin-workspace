package com.dating.im.model.event;

/**
 * Provider-agnostic, normalized IM callback event. Sealed so the dispatch {@code switch} in
 * {@code ImEventDispatcher} is exhaustive at compile time: adding a permitted variant makes that
 * switch stop compiling until the new case is handled.
 *
 * <p>Adaptors ({@code ImProviderAdaptor#parse}) map a raw vendor payload into exactly one variant.
 */
public sealed interface ImEvent
        permits MessageSentEvent, MessageBeforeSendEvent, UserOnlineEvent, UserOfflineEvent, UnknownEvent {

    /** IM vendor identifier, e.g. {@code "openim"} / {@code "tencent_im"}. */
    String provider();

    /** Raw provider callback command (for tracing), e.g. {@code "callbackAfterSendSingleMsgCommand"}. */
    String callbackCommand();
}
