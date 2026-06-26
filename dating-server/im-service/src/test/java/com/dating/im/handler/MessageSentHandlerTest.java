package com.dating.im.handler;

import com.dating.im.adaptor.OpenImAdaptor;
import com.dating.im.client.AiChatGrpcClient;
import com.dating.im.client.UserProfileGrpcClient;
import com.dating.im.client.VisionAgentGrpcClient;
import com.dating.im.model.ImMessage;
import com.dating.im.model.event.MessageSentEvent;
import com.dating.im.recorder.MessageRecorder;
import com.dating.youjianxin.proto.im.MessageType;
import com.dating.youjianxin.proto.user.UserType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MessageSentHandler} — focus on the BH→DH IMAGE branch:
 * VisionAgent.Understand result is fed into ai-chat, with placeholder fallback.
 */
@ExtendWith(MockitoExtension.class)
class MessageSentHandlerTest {

    @Mock private MessageRecorder recorder;
    @Mock private AiChatGrpcClient aiChatClient;
    @Mock private VisionAgentGrpcClient visionAgentClient;
    @Mock private UserProfileGrpcClient userProfileClient;
    @Mock private AiReplyDispatcher aiReplyDispatcher;
    @Mock private DhTypingEmitter dhTypingEmitter;

    private MessageSentHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MessageSentHandler(recorder, aiChatClient, visionAgentClient, userProfileClient,
                aiReplyDispatcher, dhTypingEmitter);
        // BH(100) → DH(200):200 是 DH,100 缺失回退 BH
        lenient().when(userProfileClient.batchGetUserType(anyList()))
                .thenReturn(Map.of(200L, UserType.USER_TYPE_DH));
        lenient().when(aiChatClient.chat(anyString(), anyString(), anyString(), anyString())).thenReturn("reply");
        // typing 仅副作用,返回 no-op 句柄即可(try-with-resources 会调 close())
        lenient().when(dhTypingEmitter.start(anyString(), anyString())).thenReturn(() -> { });
    }

    @Test
    void imageMessageUnderstoodAndFedToChat() {
        when(visionAgentClient.understand(eq(List.of("https://cdn/src.jpg")), isNull())).thenReturn("a cat");

        handler.handle(sent(image("https://cdn/src.jpg")));

        assertEquals("[Image] a cat", capturedChatMessage());
    }

    @Test
    void understandFailureFallsBackToPlaceholder() {
        when(visionAgentClient.understand(anyList(), isNull())).thenReturn(null);

        handler.handle(sent(image("https://cdn/src.jpg")));

        assertEquals(
                "[Image] (the user sent an image, but it couldn't be loaded - it may be broken or failed to send)",
                capturedChatMessage());
    }

    @Test
    void imageWithoutUrlSkipsVisionAndUsesPlaceholder() {
        handler.handle(sent(imageNoUrl()));

        verify(visionAgentClient, never()).understand(anyList(), anyString());
        assertEquals(
                "[Image] (the user sent an image, but it couldn't be loaded - it may be broken or failed to send)",
                capturedChatMessage());
    }

    @Test
    void textMessagePassesContentUnchanged() {
        handler.handle(sent(text("hello there")));

        verify(visionAgentClient, never()).understand(anyList(), anyString());
        assertEquals("hello there", capturedChatMessage());
    }

    // --- helpers ---

    private String capturedChatMessage() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(aiChatClient).chat(anyString(), eq("100"), eq("200"), cap.capture());
        return cap.getValue();
    }

    private static MessageSentEvent sent(ImMessage msg) {
        return new MessageSentEvent(msg, "callbackAfterSendSingleMsgCommand");
    }

    private ImMessage.Builder bhToDh() {
        return ImMessage.builder()
                .messageId("m1")
                .fromUserId("100")
                .toUserId("200")
                .provider("openim")
                .conversationType("C2C");
    }

    private ImMessage image(String url) {
        return bhToDh()
                .type(MessageType.IMAGE)
                .content("raw-elem")
                .putMetadata(OpenImAdaptor.METADATA_IMAGE_URL, url)
                .build();
    }

    private ImMessage imageNoUrl() {
        return bhToDh().type(MessageType.IMAGE).content("raw-elem").build();
    }

    private ImMessage text(String content) {
        return bhToDh().type(MessageType.TEXT).content(content).build();
    }
}
