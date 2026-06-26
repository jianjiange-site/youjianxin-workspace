package com.dating.im.notification;

import com.dating.im.notification.payload.MatchSuccessPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MatchSuccessNotifier} — fan-out to both parties, self/peer orientation, and
 * skipping DH recipients. {@link NotificationService} is mocked (its own behavior is covered by
 * {@link NotificationServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class MatchSuccessNotifierTest {

    @Mock
    private NotificationService notificationService;

    private MatchSuccessNotifier notifier;

    private static final NotificationService.NotificationResult OK =
            new NotificationService.NotificationResult(true, "ok", "", "", 0L);

    private MatchSuccessNotifier.Participant bh(String id) {
        return new MatchSuccessNotifier.Participant(id, "nick-" + id, "avatar/" + id + "/a.jpg", 20, false);
    }

    private MatchSuccessNotifier.Participant dh(String id) {
        return new MatchSuccessNotifier.Participant(id, "nick-" + id, "avatar/" + id + "/a.jpg", 20, true);
    }

    @Test
    void bothBh_sendsToBothWithSelfPeerOriented() {
        notifier = new MatchSuccessNotifier(notificationService);
        when(notificationService.send(any(), any(MatchSuccessPayload.class))).thenReturn(OK);

        var result = notifier.notifyMatchSuccess("m1", bh("u1"), bh("u9"), 1718000000000L);

        assertTrue(result.success());
        ArgumentCaptor<String> recv = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MatchSuccessPayload> payload = ArgumentCaptor.forClass(MatchSuccessPayload.class);
        verify(notificationService, times(2)).send(recv.capture(), payload.capture());

        // recipient u1: self=u1, peer=u9
        int i1 = recv.getAllValues().indexOf("u1");
        MatchSuccessPayload p1 = payload.getAllValues().get(i1);
        assertEquals("m1", p1.matchId());
        assertEquals("u1", p1.self().userId());
        assertEquals("u9", p1.peer().userId());
        assertEquals(NotificationKeys.MATCH_SUCCESS, p1.key());
        assertEquals(2, p1.reliabilityLevel());
        assertEquals(1718000000000L, p1.matchedAt());

        // recipient u9: self=u9, peer=u1
        int i9 = recv.getAllValues().indexOf("u9");
        MatchSuccessPayload p9 = payload.getAllValues().get(i9);
        assertEquals("u9", p9.self().userId());
        assertEquals("u1", p9.peer().userId());
    }

    @Test
    void oneDh_onlyNotifiesTheBh_butStillShowsDhAsPeer() {
        notifier = new MatchSuccessNotifier(notificationService);
        when(notificationService.send(any(), any(MatchSuccessPayload.class))).thenReturn(OK);

        // u9 is a DH → it must not receive anything, but u1 (BH) still sees u9 as peer.
        var result = notifier.notifyMatchSuccess("m1", bh("u1"), dh("u9"), 0L);

        assertTrue(result.success());
        ArgumentCaptor<MatchSuccessPayload> payload = ArgumentCaptor.forClass(MatchSuccessPayload.class);
        verify(notificationService, times(1)).send(eq("u1"), payload.capture());
        verify(notificationService, never()).send(eq("u9"), any());

        MatchSuccessPayload p = payload.getValue();
        assertEquals("u1", p.self().userId());
        assertEquals("u9", p.peer().userId());
    }

    @Test
    void bothDh_sendsNothingButSucceeds() {
        notifier = new MatchSuccessNotifier(notificationService);

        var result = notifier.notifyMatchSuccess("m1", dh("u1"), dh("u2"), 1L);

        assertTrue(result.success());
        verifyNoInteractions(notificationService);
    }

    @Test
    void ageNotFilledSerializedAsNull() {
        notifier = new MatchSuccessNotifier(notificationService);
        when(notificationService.send(any(), any(MatchSuccessPayload.class))).thenReturn(OK);

        // u1 age=0 (not filled), peer u9 age>0
        var self = new MatchSuccessNotifier.Participant("u1", "Me", "avatar/1/a.jpg", 0, false);
        var peer = new MatchSuccessNotifier.Participant("u9", "Nick", "avatar/9/a.jpg", 26, true);
        notifier.notifyMatchSuccess("m1", self, peer, 1L);

        ArgumentCaptor<MatchSuccessPayload> payload = ArgumentCaptor.forClass(MatchSuccessPayload.class);
        verify(notificationService, times(1)).send(eq("u1"), payload.capture());
        MatchSuccessPayload p = payload.getValue();
        assertNull(p.self().age());                 // 0 → null (not filled)
        assertEquals(26, p.peer().age());
    }
}
