package com.dating.im.client;

import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Signs HS256 JWTs for LiveKit room access.
 *
 * <p>Token format per https://docs.livekit.io/home/get-started/authentication
 */
@Component
public class LiveKitTokenGenerator {

    private static final Logger log = LoggerFactory.getLogger(LiveKitTokenGenerator.class);

    @Value("${livekit.api-key:}")
    private String apiKey;

    @Value("${livekit.secret-key:}")
    private String secretKey;

    @Value("${livekit.ttl-minutes:30}")
    private int ttlMinutes;

    /**
     * Generates a LiveKit access token for a specific room.
     *
     * @param userId   participant identity
     * @param roomName room to join
     * @param canPublish whether user can publish media
     */
    public String generate(String userId, String roomName, boolean canPublish) {
        if (apiKey.isEmpty() || secretKey.isEmpty()) {
            log.warn("LiveKit credentials not configured — returning empty token");
            return "";
        }

        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlMinutes * 60L);

        var key = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        String token = Jwts.builder()
                .header()
                    .add("alg", "HS256")
                    .add("typ", "JWT")
                    .and()
                .issuer(apiKey)
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .id(UUID.randomUUID().toString())
                .claims(Map.of("video", Map.of(
                        "room", roomName,
                        "roomJoin", true,
                        "canPublish", canPublish,
                        "canSubscribe", true
                )))
                .signWith(key)
                .compact();

        log.debug("LiveKit token generated: userId={} room={} canPublish={}", userId, roomName, canPublish);
        return token;
    }

    /**
     * Generates a token for a 1v1 call room.
     * Both participants use the same room name.
     */
    public String generateForCall(String userId, String peerId) {
        String roomName = "call_" + UUID.randomUUID().toString().substring(0, 8);
        log.info("New call room: room={} caller={} callee={}", roomName, userId, peerId);
        return generate(userId, roomName, true);
    }
}
