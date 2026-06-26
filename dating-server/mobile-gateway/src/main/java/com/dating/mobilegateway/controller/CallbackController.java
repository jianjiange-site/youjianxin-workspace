package com.dating.mobilegateway.controller;

import com.dating.mobilegateway.client.ImClient;
import com.dating.mobilegateway.dto.CallbackResponse;
import com.dating.youjianxin.proto.im.OnRawCallbackResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Receives OpenIM Server webhook callbacks.
 *
 * <p>Configured on OpenIM side as:
 * <pre>url: http://mobile-gateway:8080/callback/openim</pre>
 * OpenIM POSTs to {@code {url}/{callbackCommand}?contenttype=json}.
 */
@RestController
public class CallbackController {

    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);

    private final ImClient imClient;

    public CallbackController(ImClient imClient) {
        this.imClient = imClient;
    }

    @PostMapping("/callback/openim/{callbackCommand}")
    public CallbackResponse handleCallback(
            @PathVariable String callbackCommand,
            @RequestBody String rawBody,
            @RequestHeader(value = "operationID", required = false) String operationId) {

        log.info("OpenIM callback: command={} operationID={}", callbackCommand, operationId);

        try {
            OnRawCallbackResponse resp = imClient.onRawCallback("openim",
                    rawBody.getBytes(StandardCharsets.UTF_8));
            if (!resp.getSuccess()) {
                log.warn("OpenIM callback failed: command={} message={}", callbackCommand, resp.getMessage());
                return CallbackResponse.fail(-1, resp.getMessage());
            }
            // 中立业务决策码 → OpenIM 应答:code!=0 拦截(nextCode=1),否则放行。
            if (resp.getCode() != 0) {
                log.info("OpenIM callback reject: command={} code={} message={}",
                        callbackCommand, resp.getCode(), resp.getMessage());
                return CallbackResponse.reject(resp.getCode(), resp.getMessage());
            }
            return CallbackResponse.ok();
        } catch (Exception e) {
            log.error("Failed to process OpenIM callback: command={}", callbackCommand, e);
            return CallbackResponse.fail(-1, e.getMessage());
        }
    }
}
