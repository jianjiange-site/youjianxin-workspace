package com.dating.im.recorder;

import com.dating.im.model.ImMessage;

/** Persists chat messages. */
public interface MessageRecorder {

    /** Saves a message. */
    void save(ImMessage msg, String routeType);
}
