package com.dating.im.recorder;

import com.dating.im.model.ImMessage;
import com.dating.im.model.entity.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@Component
public class JpaMessageRecorder implements MessageRecorder {

    private static final Logger log = LoggerFactory.getLogger(JpaMessageRecorder.class);

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public void save(ImMessage msg, String routeType) {
        ChatMessage entity = new ChatMessage(
                msg.messageId(),
                msg.fromUserId(),
                msg.toUserId(),
                msg.content(),
                msg.type(),
                msg.conversationType(),
                msg.provider(),
                routeType,
                msg.timestamp()
        );
        em.persist(entity);
        log.debug("Message saved: msgId={} routeType={}", msg.messageId(), routeType);
    }
}
