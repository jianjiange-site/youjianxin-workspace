package com.dating.im.notification.payload;

import com.dating.im.notification.NotificationKeys;
import com.dating.im.notification.NotificationPayload;

/**
 * Swipe-card match succeeded — a one-shot online signal (popup / red dot), not persisted.
 *
 * <p>Distinct from {@code MatchWelcomePayload}: this is a transient signal, that is a persisted
 * conversation system message.
 *
 * <p>Carries both matched parties so the client can render an "It's a Match!" screen directly.
 * The notification is delivered per recipient (A and B each get one), so {@code self} is always the
 * recipient and {@code peer} is the other party — the client never has to figure out which one it is.
 *
 * <p>Serialized wire form (the {@code data} field of the business notification):
 * <pre>{@code
 * {
 *   "matchId": "m1",
 *   "self": {"userId": "u1", "nickname": "Me",   "avatarKey": "avatar/1/202606/x.jpg", "age": 28},
 *   "peer": {"userId": "u9", "nickname": "Nick", "avatarKey": "avatar/9/202606/a.jpg", "age": 26},
 *   "matchedAt": 1718000000000
 * }
 * }</pre>
 * {@code avatarKey} is an object key — the client builds the URL itself; {@code age} may be
 * {@code null}/{@code 0} when not filled.
 *
 * @param matchId   id of this match (the persisted pairing record); the stable correlation key for
 *                  the whole match flow. The client uses it to correlate this transient signal with
 *                  the persisted {@code match_welcome} system message (same id), to open the matched
 *                  conversation / match-detail screen, and to reference the pairing in later actions
 *                  (e.g. unmatch / report). {@code peer.userId} says *who* you matched with; matchId
 *                  says *which* match this is.
 * @param self      the recipient's own participant info
 * @param peer      the matched peer's participant info
 * @param matchedAt match time, UTC epoch millis (client renders in the user's local tz)
 */
public record MatchSuccessPayload(String matchId, Participant self, Participant peer,
                                  long matchedAt) implements NotificationPayload {

    /**
     * One side of a match.
     *
     * @param userId    user id
     * @param nickname  nickname (for display)
     * @param avatarKey avatar object key — the client builds the URL itself; never a full URL
     * @param age       age in years; {@code null}/{@code 0} means not filled
     */
    public record Participant(String userId, String nickname, String avatarKey, Integer age) {
    }

    @Override
    public String key() {
        return NotificationKeys.MATCH_SUCCESS;
    }

    @Override
    public int reliabilityLevel() {
        return 2;
    }
}
