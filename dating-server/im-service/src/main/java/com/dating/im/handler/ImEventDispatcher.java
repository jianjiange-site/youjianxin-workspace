package com.dating.im.handler;

import com.dating.im.model.CallbackResult;
import com.dating.im.model.event.ImEvent;
import com.dating.im.model.event.MessageBeforeSendEvent;
import com.dating.im.model.event.MessageSentEvent;
import com.dating.im.model.event.UnknownEvent;
import com.dating.im.model.event.UserOfflineEvent;
import com.dating.im.model.event.UserOnlineEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Routes a parsed {@link ImEvent} to its handler.
 *
 * <p>The dispatch {@code switch} is a pattern switch over the sealed {@link ImEvent} with <b>no
 * {@code default}</b> — so adding a permitted variant makes this stop compiling until the new case is
 * handled. {@link UnknownEvent} covers "an unmodeled provider command"; it must NOT be turned into a
 * catch-all for modeled types, or that compile-time guarantee is lost.
 */
@Component
public class ImEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ImEventDispatcher.class);

    private final MessageSentHandler messageSentHandler;
    private final BeforeSendHandler beforeSendHandler;
    private final PresenceHandler presenceHandler;

    public ImEventDispatcher(MessageSentHandler messageSentHandler,
                             BeforeSendHandler beforeSendHandler,
                             PresenceHandler presenceHandler) {
        this.messageSentHandler = messageSentHandler;
        this.beforeSendHandler = beforeSendHandler;
        this.presenceHandler = presenceHandler;
    }

    public CallbackResult dispatch(ImEvent event) {
        return switch (event) {
            case MessageSentEvent e -> {
                messageSentHandler.handle(e);
                yield CallbackResult.ok();
            }
            case MessageBeforeSendEvent e -> beforeSendHandler.handle(e);
            case UserOnlineEvent e -> {
                presenceHandler.online(e);
                yield CallbackResult.ok();
            }
            case UserOfflineEvent e -> {
                presenceHandler.offline(e);
                yield CallbackResult.ok();
            }
            case UnknownEvent e -> {
                log.info("Unhandled IM callback: command='{}' provider={}", e.callbackCommand(), e.provider());
                yield CallbackResult.ok();
            }
        };
    }
}
