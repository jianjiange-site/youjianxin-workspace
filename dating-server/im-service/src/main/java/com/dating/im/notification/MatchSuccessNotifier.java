package com.dating.im.notification;

import com.dating.im.notification.payload.MatchSuccessPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fans out a swipe match-success signal to both matched parties.
 *
 * <p>Given both participants, sends one {@code match_success} business notification to each non-DH
 * recipient — to A with {@code self=A / peer=B}, to B with {@code self=B / peer=A} — so the client
 * renders an "It's a Match!" screen without having to figure out which side it is.
 *
 * <p>DH (digital human) recipients are skipped: the caller already assembles each side's profile, so
 * it flags DHs via {@link Participant#isDh()} and this never has to call back to user-service. Note a
 * DH that is the <em>peer</em> is still shown to the BH recipient — only a DH <em>recipient</em> is
 * not notified (a bot has no client to pop a match screen).
 */
@Component
public class MatchSuccessNotifier {

    private static final Logger log = LoggerFactory.getLogger(MatchSuccessNotifier.class);

    private final NotificationService notificationService;

    public MatchSuccessNotifier(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * One side of the match, as supplied by the caller.
     *
     * @param userId    user id (non-blank)
     * @param nickname  nickname for display
     * @param avatarKey avatar object key — never a full URL
     * @param age       age in years; {@code <= 0} is treated as not filled
     * @param isDh      whether this user is a digital human; DH recipients are not notified
     */
    public record Participant(String userId, String nickname, String avatarKey, int age, boolean isDh) {
    }

    /** Outcome of a fan-out. */
    public record Result(boolean success, String message) {
    }

    /**
     * Notifies both parties, skipping any DH recipient.
     *
     * @param matchId   match id
     * @param a         one participant
     * @param b         the other participant
     * @param matchedAt match time, UTC epoch millis; {@code <= 0} is replaced with now
     * @return aggregate result; success iff every notification that should have been sent succeeded
     *         (skipped DHs do not count as failures; both-DH yields success with nothing sent)
     */
    public Result notifyMatchSuccess(String matchId, Participant a, Participant b, long matchedAt) {
        long when = matchedAt > 0 ? matchedAt : System.currentTimeMillis();

        List<String> failures = new ArrayList<>();
        int sent = 0;

        // Each non-DH recipient gets self = themselves, peer = the other side.
        for (Participant[] pair : new Participant[][]{{a, b}, {b, a}}) {
            Participant self = pair[0];
            Participant peer = pair[1];
            if (self.isDh()) {
                log.debug("match_success skip DH recipient userId={} matchId={}", self.userId(), matchId);
                continue;
            }
            MatchSuccessPayload payload =
                    new MatchSuccessPayload(matchId, toPayload(self), toPayload(peer), when);
            NotificationService.NotificationResult r = notificationService.send(self.userId(), payload);
            sent++;
            if (!r.success()) {
                failures.add(self.userId() + ": " + r.message());
            }
        }

        if (sent == 0) {
            return new Result(true, "ok (no recipient: both DH)");
        }
        if (!failures.isEmpty()) {
            return new Result(false, "send failed: " + String.join("; ", failures));
        }
        return new Result(true, "ok");
    }

    private static MatchSuccessPayload.Participant toPayload(Participant p) {
        Integer age = p.age() > 0 ? p.age() : null;
        return new MatchSuccessPayload.Participant(p.userId(), p.nickname(), p.avatarKey(), age);
    }
}
