package com.dating.im.sender;

import com.dating.im.model.ImMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Sends messages via the Tencent IM REST API.
 *
 * <p>API doc: <a href="https://cloud.tencent.com/document/product/269/2282">Single Chat Message</a>
 */
@Component
public class TencentImSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(TencentImSender.class);
    private static final String PROVIDER = "tencent_im";

    private final RestTemplate restTemplate;

    @Value("${IM_TENCENT_SDK_APP_ID:0}")
    private long sdkAppId;

    @Value("${IM_TENCENT_IDENTIFIER:}")
    private String identifier;

    @Value("${IM_TENCENT_KEY:}")
    private String key;

    @Value("${IM_TENCENT_BASE_URL:https://console.tim.qq.com}")
    private String baseUrl;

    public TencentImSender(@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
                           RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public boolean supports(String provider) {
        return PROVIDER.equals(provider);
    }

    @Override
    public boolean send(ImMessage msg) {
        if (sdkAppId == 0 || identifier.isEmpty() || key.isEmpty()) {
            log.warn("Tencent IM credentials not configured, skipping send: msgId={}", msg.messageId());
            return false;
        }

        String userSig = generateUserSig(identifier, key);
        int random = new Random().nextInt(Integer.MAX_VALUE);
        String url = String.format("%s/v4/openim/sendmsg?sdkappid=%d&identifier=%s&usersig=%s&random=%d",
                baseUrl, sdkAppId, identifier, userSig, random);

        Map<String, Object> body = buildRequestBody(msg);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            log.debug("Sending TIM message: msgId={} to={}", msg.messageId(), msg.toUserId());
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("TIM message sent: msgId={}", msg.messageId());
                return true;
            } else {
                log.error("TIM send failed: msgId={} status={} body={}",
                        msg.messageId(), response.getStatusCode(), response.getBody());
                return false;
            }
        } catch (Exception e) {
            log.error("TIM send error: msgId={}", msg.messageId(), e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildRequestBody(ImMessage msg) {
        return Map.of(
                "From_Account", msg.fromUserId(),
                "To_Account", msg.toUserId(),
                "MsgRandom", new Random().nextInt(Integer.MAX_VALUE),
                "MsgBody", List.of(Map.of(
                        "MsgType", "TIMTextElem",
                        "MsgContent", Map.of("Text", msg.content())
                ))
        );
    }

    // TODO: Replace with proper TLSSigAPIv2 signature generation
    private String generateUserSig(String identifier, String key) {
        // Stub — Tencent IM requires HMAC-SHA256 based UserSig.
        // The real implementation should use the TLSSigAPIv2 library.
        log.warn("UserSig generation not implemented — Tencent IM send will fail without a valid sig");
        return "";
    }
}
