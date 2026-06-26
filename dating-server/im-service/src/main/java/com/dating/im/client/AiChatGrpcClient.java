package com.dating.im.client;

import com.dating.youjianxin.proto.chat.ChatAgentGrpc;
import com.dating.youjianxin.proto.chat.ChatRequest;
import com.dating.youjianxin.proto.chat.ChatResponse;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gRPC client for calling the ai-chat service to generate AI replies.
 */
@Component
public class AiChatGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(AiChatGrpcClient.class);

    @GrpcClient("ai-chat")
    private ChatAgentGrpc.ChatAgentBlockingStub chatAgentStub;

    /**
     * Calls ai-chat to generate an AI reply for a BH→DH message.
     *
     * @param threadId   conversation thread ID (used as LangGraph checkpoint key)
     * @param fromUserId the BH (real user) sending the message
     * @param toUserId   the DH (persona) receiving the message
     * @param message    the user's message content
     * @return AI-generated reply content, or {@code null} on failure
     */
    public String chat(String threadId, String fromUserId, String toUserId, String message) {
        ChatRequest request = ChatRequest.newBuilder()
                .setThreadId(threadId)
                .setFromUserId(fromUserId)
                .setToUserId(toUserId)
                .setMessage(message)
                .build();

        try {
            ChatResponse response = chatAgentStub.chat(request);
            log.info("ai-chat reply: threadId={} length={}", threadId,
                    response.getContent() != null ? response.getContent().length() : 0);
            return response.getContent();
        } catch (StatusRuntimeException e) {
            log.error("ai-chat call failed: threadId={} status={}", threadId, e.getStatus(), e);
            return null;
        }
    }
}
