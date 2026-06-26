package com.dating.im.handler;

import com.dating.im.model.event.UserOfflineEvent;
import com.dating.im.model.event.UserOnlineEvent;
import com.dating.im.service.PresenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Seam for presence callbacks (user online/offline, e.g. OpenIM {@code callbackUserOnlineCommand} /
 * {@code callbackUserOfflineCommand}).
 *
 * <p>Delegates to {@link PresenceService}, which maintains the online set (Redis ZSet
 * {@code im:presence:online}) and persists online-session durations to PG
 * ({@code user_online_session}).
 */
@Component
public class PresenceHandler {

    private static final Logger log = LoggerFactory.getLogger(PresenceHandler.class);

    private final PresenceService presenceService;

    public PresenceHandler(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    public void online(UserOnlineEvent event) {
        log.info("user online: userId={} platform={}", event.userId(), event.platform());
        presenceService.online(event);
    }

    public void offline(UserOfflineEvent event) {
        log.info("user offline: userId={} platform={}", event.userId(), event.platform());
        presenceService.offline(event);
    }
}
