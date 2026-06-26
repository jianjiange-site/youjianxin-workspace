package com.dating.im.notification;

import com.dating.im.client.OpenImApiClient;
import com.dating.im.notification.payload.MatchSuccessPayload;
import com.dating.im.notification.payload.MatchWelcomePayload;
import com.dating.im.notification.payload.TypingPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationService} — validation branches, default-value fallback,
 * OpenIM response mapping, and the typed {@link NotificationPayload} entry points. The OpenIM REST
 * client is mocked (it is a REST boundary, not a DB).
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private OpenImApiClient openImClient;

    private NotificationService service;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new NotificationService(openImClient, om);
        ReflectionTestUtils.setField(service, "systemNotificationUserId", "imAdmin");
    }

    // ---- Low-level sendBusinessNotification validation / mapping ----

    @Test
    void rejectsBlankKey() {
        var r = service.sendBusinessNotification("u1", "u2", "", "  ", "{}", false, 1);
        assertFalse(r.success());
        verifyNoInteractions(openImClient);
    }

    @Test
    void rejectsBlankData() {
        var r = service.sendBusinessNotification("u1", "u2", "", "typing", "  ", false, 1);
        assertFalse(r.success());
        verifyNoInteractions(openImClient);
    }

    @Test
    void rejectsBothReceivers() {
        var r = service.sendBusinessNotification("u1", "u2", "g1", "typing", "{}", false, 1);
        assertFalse(r.success());
        verifyNoInteractions(openImClient);
    }

    @Test
    void rejectsNoReceiver() {
        var r = service.sendBusinessNotification("u1", "", "", "typing", "{}", false, 1);
        assertFalse(r.success());
        verifyNoInteractions(openImClient);
    }

    @Test
    void successAppliesDefaultSenderAndReliability() throws Exception {
        JsonNode ok = om.readTree(
                "{\"errCode\":0,\"data\":{\"clientMsgID\":\"cid\",\"serverMsgID\":\"sid\",\"sendTime\":123}}");
        when(openImClient.sendBusinessNotification(
                anyString(), anyString(), any(), anyString(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(ok);

        // blank sendUserId -> systemNotificationUserId; reliabilityLevel 0 -> 1
        var r = service.sendBusinessNotification("", "u2", "", "typing", "{}", false, 0);

        assertTrue(r.success());
        assertEquals("cid", r.clientMsgId());
        assertEquals("sid", r.serverMsgId());
        assertEquals(123L, r.sendTime());

        ArgumentCaptor<String> sender = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> reliability = ArgumentCaptor.forClass(Integer.class);
        verify(openImClient).sendBusinessNotification(
                sender.capture(), eq("u2"), eq(""), eq("typing"), eq("{}"), eq(false), reliability.capture());
        assertEquals("imAdmin", sender.getValue());
        assertEquals(1, reliability.getValue());
    }

    @Test
    void mapsOpenImErrorToFailure() throws Exception {
        JsonNode err = om.readTree("{\"errCode\":1004,\"errMsg\":\"RecordNotFoundError\"}");
        when(openImClient.sendBusinessNotification(
                anyString(), anyString(), any(), anyString(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(err);

        var r = service.sendBusinessNotification("sys", "u2", "", "match_success", "{}", true, 2);

        assertFalse(r.success());
        assertTrue(r.message().contains("1004"));
    }

    // ---- Typed NotificationPayload entry points ----

    @Test
    void typedSendDerivesKeyDataPersistAndReliabilityFromPayload() throws Exception {
        JsonNode ok = om.readTree(
                "{\"errCode\":0,\"data\":{\"clientMsgID\":\"cid\",\"serverMsgID\":\"sid\",\"sendTime\":1}}");
        when(openImClient.sendBusinessNotification(
                anyString(), anyString(), any(), anyString(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(ok);

        // welcome is a persisted system message with guaranteed delivery
        var r = service.send("u2", new MatchWelcomePayload("m1", "c1", "你们配对了"));
        assertTrue(r.success());

        ArgumentCaptor<String> sender = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> sendMsg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> reliability = ArgumentCaptor.forClass(Integer.class);
        verify(openImClient).sendBusinessNotification(
                sender.capture(), eq("u2"), any(), key.capture(), data.capture(),
                sendMsg.capture(), reliability.capture());

        assertEquals("imAdmin", sender.getValue());        // blank sender -> system account
        assertEquals("match_welcome", key.getValue());     // key from payload
        assertTrue(sendMsg.getValue());                    // persist() == true
        assertEquals(2, reliability.getValue());           // reliabilityLevel() == 2

        JsonNode d = om.readTree(data.getValue());         // data is valid JSON with payload fields
        assertEquals("m1", d.path("matchId").asText());
        assertEquals("c1", d.path("conversationId").asText());
        assertEquals("你们配对了", d.path("text").asText());
    }

    @Test
    void typingPayloadIsOnlineOnlyAndNotPersisted() throws Exception {
        JsonNode ok = om.readTree("{\"errCode\":0,\"data\":{}}");
        when(openImClient.sendBusinessNotification(
                anyString(), anyString(), any(), anyString(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(ok);

        service.send("u2", TypingPayload.start("u1", "c1"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> sendMsg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> reliability = ArgumentCaptor.forClass(Integer.class);
        verify(openImClient).sendBusinessNotification(
                anyString(), eq("u2"), any(), key.capture(), anyString(),
                sendMsg.capture(), reliability.capture());

        assertEquals("typing", key.getValue());
        assertFalse(sendMsg.getValue());                   // persist() default false
        assertEquals(1, reliability.getValue());           // reliabilityLevel() default 1
    }

    @Test
    void payloadKeysAreUniqueAndDeclaredInNotificationKeys() {
        List<NotificationPayload> all = List.of(
                TypingPayload.start("u1", "c1"),
                new MatchSuccessPayload("m1",
                        new MatchSuccessPayload.Participant("u1", "Me", "avatar/1/202606/x.jpg", 28),
                        new MatchSuccessPayload.Participant("u9", "Nick", "avatar/9/202606/a.jpg", 26),
                        1L),
                new MatchWelcomePayload("m1", "c1", "你们配对了"));
        Set<String> known = Set.of(
                NotificationKeys.TYPING, NotificationKeys.MATCH_SUCCESS, NotificationKeys.MATCH_WELCOME);

        Set<String> seen = new HashSet<>();
        for (NotificationPayload p : all) {
            assertTrue(known.contains(p.key()), "key not declared in NotificationKeys: " + p.key());
            assertTrue(seen.add(p.key()), "duplicate key: " + p.key());
        }
    }

    @Test
    void imageFieldSerializedAsObjectKeyNotFullUrl() {
        String data = new MatchSuccessPayload("m1",
                new MatchSuccessPayload.Participant("u1", "Me", "avatar/1/202606/x.jpg", 28),
                new MatchSuccessPayload.Participant("u9", "Nick", "avatar/9/202606/a.jpg", 26),
                1L).toData(om);
        assertTrue(data.contains("avatarKey"));
        assertFalse(data.contains("http://"));
        assertFalse(data.contains("https://"));
    }

    @Test
    void typingStateSerializedAsBooleanField() {
        String start = TypingPayload.start("u1", "c1", 8).toData(om);
        assertTrue(start.contains("\"typing\":true"));
        assertTrue(start.contains("\"displaySeconds\":8"));
        assertTrue(TypingPayload.stop("u1", "c1").toData(om).contains("\"typing\":false"));
    }
}
