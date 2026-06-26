package com.dating.im.sender;

import com.dating.im.client.OpenImApiClient;
import com.dating.im.model.ImMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends messages via OpenIM Server REST API.
 */
@Component
public class OpenImSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(OpenImSender.class);
    private static final String PROVIDER = "openim";

    private final OpenImApiClient apiClient;

    public OpenImSender(OpenImApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public boolean supports(String provider) {
        return PROVIDER.equals(provider);
    }

    @Override
    public boolean send(ImMessage msg) {
        String content = msg.content();
        if (content == null || content.isEmpty()) {
            log.warn("Empty content, skipping send: msgId={}", msg.messageId());
            return false;
        }

        boolean ok = apiClient.sendMsg(
                msg.fromUserId(),
                msg.toUserId(),
                content
        );

        if (ok) {
            log.info("OpenIM message sent: msgId={} from={} to={}", msg.messageId(),
                    msg.fromUserId(), msg.toUserId());
        }
        return ok;
    }
}
