package com.dating.im.adaptor;

import com.dating.im.model.event.ImEvent;

/** Parses raw IM callbacks from different vendors into the vendor-agnostic {@link ImEvent} model. */
public interface ImProviderAdaptor {

    /** Returns {@code true} if this adaptor handles the given provider. */
    boolean supports(String provider);

    /**
     * Parses a raw callback payload into a vendor-agnostic {@link ImEvent}. Never returns {@code null};
     * unrecognized commands map to {@link com.dating.im.model.event.UnknownEvent}.
     */
    ImEvent parse(byte[] rawPayload);
}
