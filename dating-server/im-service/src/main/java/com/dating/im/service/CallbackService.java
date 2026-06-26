package com.dating.im.service;

import com.dating.im.adaptor.ImProviderAdaptor;
import com.dating.im.handler.ImEventDispatcher;
import com.dating.im.model.CallbackResult;
import com.dating.im.model.event.ImEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Handles incoming IM callbacks: adaptor lookup, parse to {@link ImEvent}, then dispatch.
 * Shared by gRPC and HTTP entry points.
 */
@Service
public class CallbackService {

    private static final Logger log = LoggerFactory.getLogger(CallbackService.class);

    private final ImEventDispatcher dispatcher;
    private final List<ImProviderAdaptor> adaptors;

    public CallbackService(ImEventDispatcher dispatcher, List<ImProviderAdaptor> adaptors) {
        this.dispatcher = dispatcher;
        this.adaptors = adaptors;
    }

    /**
     * Handles a raw callback payload from the given provider.
     *
     * @return the result to relay back to the provider; {@link CallbackResult#unsupported} if no
     *         adaptor handles the provider
     */
    public CallbackResult handleCallback(String provider, byte[] payload) {
        ImProviderAdaptor adaptor = adaptors.stream()
                .filter(a -> a.supports(provider))
                .findFirst()
                .orElse(null);

        if (adaptor == null) {
            log.warn("No adaptor for provider={}", provider);
            return CallbackResult.unsupported(provider);
        }

        ImEvent event = adaptor.parse(payload);
        return dispatcher.dispatch(event);
    }
}
