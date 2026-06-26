package com.dating.payment.executor.paypal;

import com.dating.youjianxin.proto.payment.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * PayPal 支付执行器 — 封装 PayPal REST API 调用。
 *
 * <p>职责：创建订单、捕获付款、处理 Webhook 回调。
 * 与渠道无关的编排逻辑（如订单持久化、幂等校验）不在本层处理，
 * 由上层 {@code PaymentService} 负责。</p>
 */
@Component
public class PaypalExecutor {

    private static final Logger log = LoggerFactory.getLogger(PaypalExecutor.class);

    private static final String SANDBOX_BASE = "https://api-m.sandbox.paypal.com";
    private static final String LIVE_BASE    = "https://api-m.paypal.com";

    private final RestTemplate restTemplate;
    private final String clientId;
    private final String clientSecret;
    private final String baseUrl;
    private final String webhookId;
    /** PayPal 凭据是否齐全；为 false 时禁用本执行器，对外方法抛明确错误而非启动崩溃。 */
    private final boolean configured;

    public PaypalExecutor(RestTemplate restTemplate,
                          @Value("${paypal.client-id}") String clientId,
                          @Value("${paypal.client-secret}") String clientSecret,
                          @Value("${paypal.environment}") String environment,
                          @Value("${paypal.webhook-id}") String webhookId) {
        this.restTemplate = restTemplate;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.baseUrl = "live".equalsIgnoreCase(environment) ? LIVE_BASE : SANDBOX_BASE;
        this.webhookId = webhookId;
        this.configured = StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
        if (!configured) {
            log.warn("PayPal 未配置 (PAYPAL_CLIENT_ID/SECRET 为空)，PayPal 充值/提现通道已禁用；配置后重启生效");
        }
    }

    /**
     * 校验 PayPal 凭据已配置；未配置则抛明确错误，避免上游收到费解的 401。
     * 仅在真正发起 PayPal 调用的对外方法入口调用，保证缺凭据不影响服务启动与其它通道。
     */
    private void requireConfigured() {
        if (!configured) {
            throw new IllegalStateException("PayPal 通道未配置：请设置 PAYPAL_CLIENT_ID / PAYPAL_CLIENT_SECRET");
        }
    }

    /**
     * 创建 PayPal 订单（intent=CAPTURE）。
     *
     * @param orderId   内部订单号（用于关联）
     * @param amountCent 金额，单位：分
     * @param currency  货币代码，如 USD / EUR
     * @param returnUrl 用户完成支付后 PayPal 跳回的 URL
     * @param cancelUrl 用户取消支付后 PayPal 跳回的 URL
     * @return CreateOrderResponse 其中 ext_order_id=paypal order id，checkout_url=approval 链接
     */
    public CreateOrderResponse createOrder(String orderId, long amountCent, String currency,
                                           String returnUrl, String cancelUrl) {
        requireConfigured();
        try {
            String token = getAccessToken();
            String payload = buildCreateOrderPayload(orderId, amountCent, currency, returnUrl, cancelUrl);
            log.debug("[EXECUTOR] createOrder request: POST {} | payload={} | PayPal-Request-Id={}",
                    baseUrl + "/v2/checkout/orders", payload, orderId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("PayPal-Request-Id", orderId);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/v2/checkout/orders",
                    HttpMethod.POST,
                    new HttpEntity<>(payload, headers),
                    Map.class);

            Map<String, Object> body = resp.getBody();
            String paypalOrderId = (String) body.get("id");
            String status = (String) body.get("status");
            log.info("[EXECUTOR] createOrder response: statusCode={}, paypalOrderId={}, paypalStatus={}",
                    resp.getStatusCode(), paypalOrderId, status);
            log.debug("[EXECUTOR] createOrder response body: {}", body);

            String approvalUrl = extractApprovalUrl(body);

            return CreateOrderResponse.newBuilder()
                    .setBase(BaseResponse.newBuilder()
                            .setCode(0)
                            .setMessage("OK"))
                    .setOrderId(orderId)
                    .setStatus("APPROVAL_PENDING".equals(status) || "CREATED".equals(status)
                            ? OrderStatus.PENDING : OrderStatus.FAILED)
                    .setExtOrderId(paypalOrderId)
                    .setCheckoutUrl(approvalUrl != null ? approvalUrl : "")
                    .build();

        } catch (Exception e) {
            log.error("[EXECUTOR] createOrder failed: orderId={}, error={}", orderId, e.getMessage(), e);
            return CreateOrderResponse.newBuilder()
                    .setBase(BaseResponse.newBuilder()
                            .setCode(2001)
                            .setMessage("PayPal order creation failed: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * 捕获 PayPal 订单（买家批准后调用）。
     *
     * @param paypalOrderId PayPal 返回的 order id
     * @return CaptureResult
     */
    public CaptureResult captureOrder(String paypalOrderId) {
        requireConfigured();
        try {
            String token = getAccessToken();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            log.debug("[EXECUTOR] captureOrder request: POST {}",
                    baseUrl + "/v2/checkout/orders/" + paypalOrderId + "/capture");

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/v2/checkout/orders/" + paypalOrderId + "/capture",
                    HttpMethod.POST,
                    new HttpEntity<>("{}", headers),
                    Map.class);

            Map<String, Object> body = resp.getBody();
            String status = (String) body.get("status");
            String captureId = extractCaptureId(body);

            log.info("[EXECUTOR] captureOrder response: statusCode={}, paypalOrderId={}, status={}, captureId={}",
                    resp.getStatusCode(), paypalOrderId, status, captureId);
            log.debug("[EXECUTOR] captureOrder response body: {}", body);

            return new CaptureResult(
                    "COMPLETED".equals(status),
                    captureId,
                    paypalOrderId);

        } catch (Exception e) {
            // ORDER_ALREADY_CAPTURED — webhook already captured, treat as success
            if (e instanceof HttpClientErrorException.UnprocessableEntity) {
                String respBody = ((HttpClientErrorException.UnprocessableEntity) e).getResponseBodyAsString();
                if (respBody != null && respBody.contains("ORDER_ALREADY_CAPTURED")) {
                    log.info("[EXECUTOR] captureOrder: order already captured (idempotent), paypalOrderId={}", paypalOrderId);
                    return new CaptureResult(true, null, paypalOrderId);
                }
            }
            log.error("[EXECUTOR] captureOrder failed: paypalOrderId={}, error={}",
                    paypalOrderId, e.getMessage(), e);
            return new CaptureResult("PayPal capture failed: " + e.getMessage());
        }
    }

    /**
     * 处理 PayPal Webhook 回调。
     *
     * @param rawBody 请求体原始 JSON 字符串
     * @param headers HTTP 请求头（含 PayPal-Transmission-Id 等验签字段）
     * @return WebhookResponse
     */
    public WebhookResponse handleWebhook(String rawBody, Map<String, String> headers) {
        requireConfigured();
        try {
            if (webhookId != null && !webhookId.isBlank()) {
                boolean verified = verifyWebhookSignature(rawBody, headers);
                if (!verified) {
                    log.warn("PayPal webhook signature verification FAILED");
                    return WebhookResponse.newBuilder()
                            .setBase(BaseResponse.newBuilder()
                                    .setCode(2003)
                                    .setMessage("Webhook signature verification failed"))
                            .build();
                }
            } else {
                log.warn("PAYPAL_WEBHOOK_ID not configured, skipping webhook signature verification");
            }

            String eventType = parseEventType(rawBody);
            String customId = parseCustomId(rawBody);
            String captureId = parseWebhookResourceId(rawBody);
            String paypalOrderId = parsePayPalOrderId(rawBody);
            log.info("PayPal webhook received: eventType={}, customId={}, captureId={}, paypalOrderId={}, rawBody={}",
                    eventType, customId, captureId, paypalOrderId, rawBody);

            switch (eventType) {
                case "PAYMENT.CAPTURE.COMPLETED":
                    log.info("Payment capture completed: orderId={}, paypalOrderId={}, captureId={}",
                            customId, paypalOrderId, captureId);
                    break;
                case "PAYMENT.CAPTURE.DENIED":
                    log.warn("Payment capture denied: orderId={}, paypalOrderId={}, captureId={}",
                            customId, paypalOrderId, captureId);
                    break;
                case "PAYMENT.CAPTURE.REFUNDED":
                    log.info("Payment refunded: orderId={}, paypalOrderId={}, captureId={}",
                            customId, paypalOrderId, captureId);
                    break;
                case "CHECKOUT.ORDER.APPROVED":
                    // TODO: 暂未完整实现——理想逻辑：
                    //   1. 根据 resource.id (paypalOrderId) 查询 payment_orders 表确认订单当前状态
                    //   2. 若订单尚未 PAID（未被 capture），调 captureOrder() 自动完成收款
                    //   3. 更新 payment_orders 状态 + 发放权益
                    //   4. 若已 PAID，直接忽略（防止重复 capture）
                    // 当前兜底：对已 approval 的订单尝试 capture（幂等由 PayPal 保证）
                    String orderIdForCapture = parseWebhookResourceId(rawBody);
                    if (orderIdForCapture != null && !orderIdForCapture.isEmpty()) {
                        log.info("CHECKOUT.ORDER.APPROVED received, auto-capturing order: paypalOrderId={}, customId={}",
                                orderIdForCapture, customId);
                        try {
                            CaptureResult captureResult = captureOrder(orderIdForCapture);
                            log.info("CHECKOUT.ORDER.APPROVED auto-capture result: completed={}, transactionId={}",
                                    captureResult.isCompleted(), captureResult.getTransactionId());
                        } catch (Exception e) {
                            log.warn("CHECKOUT.ORDER.APPROVED auto-capture failed: paypalOrderId={}, error={}",
                                    orderIdForCapture, e.getMessage(), e);
                        }
                    }
                    break;
                default:
                    log.debug("Unhandled PayPal webhook event: {}", eventType);
            }

            return WebhookResponse.newBuilder()
                    .setBase(BaseResponse.newBuilder()
                            .setCode(0)
                            .setMessage("OK"))
                    .build();

        } catch (Exception e) {
            log.error("Failed to handle PayPal webhook", e);
            return WebhookResponse.newBuilder()
                    .setBase(BaseResponse.newBuilder()
                            .setCode(2004)
                            .setMessage("Webhook handling failed: " + e.getMessage()))
                    .build();
        }
    }

    private String getAccessToken() {
        log.debug("[EXECUTOR] Requesting PayPal OAuth2 token from {}", baseUrl + "/v1/oauth2/token");
        HttpHeaders headers = new HttpHeaders();
        String auth = clientId + ":" + clientSecret;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encodedAuth);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");

        ResponseEntity<Map> resp = restTemplate.exchange(
                baseUrl + "/v1/oauth2/token",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        String token = (String) resp.getBody().get("access_token");
        log.debug("[EXECUTOR] OAuth2 token obtained (masked): {}...",
                token != null ? token.substring(0, Math.min(10, token.length())) + "..." : "null");
        return token;
    }

    private static String buildCreateOrderPayload(String orderNo, long amountCent, String currency,
                                                    String returnUrl, String cancelUrl) {
        // PayPal 金额单位：元，从分转换
        String amountValue = String.format("%.2f", amountCent / 100.0);
        return String.format(
                "{\"intent\":\"CAPTURE\"," +
                "\"purchase_units\":[{\"amount\":{\"currency_code\":\"%s\",\"value\":\"%s\"},\"custom_id\":\"%s\"}]," +
                "\"application_context\":{\"return_url\":\"%s\",\"cancel_url\":\"%s\"}}",
                currency, amountValue, escape(orderNo), escape(returnUrl), escape(cancelUrl));
    }

    private static String extractApprovalUrl(Map<String, Object> body) {
        Object linksObj = body.get("links");
        if (linksObj instanceof java.util.List) {
            for (Object item : (java.util.List) linksObj) {
                if (item instanceof Map) {
                    Map<?, ?> link = (Map<?, ?>) item;
                    if ("approve".equals(link.get("rel"))) {
                        return (String) link.get("href");
                    }
                }
            }
        }
        return null;
    }

    private static String extractCaptureId(Map<String, Object> body) {
        try {
            java.util.List<Map<String, Object>> purchaseUnits =
                    (java.util.List<Map<String, Object>>) body.get("purchase_units");
            if (purchaseUnits != null && !purchaseUnits.isEmpty()) {
                Map<String, Object> payments =
                        (Map<String, Object>) purchaseUnits.get(0).get("payments");
                if (payments != null) {
                    java.util.List<Map<String, Object>> captures =
                            (java.util.List<Map<String, Object>>) payments.get("captures");
                    if (captures != null && !captures.isEmpty()) {
                        return (String) captures.get(0).get("id");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract capture id from PayPal response", e);
        }
        return null;
    }

    private boolean verifyWebhookSignature(String rawBody, Map<String, String> headers) {
        String transmissionId = headers.get("paypal-transmission-id");
        String transmissionTime = headers.get("paypal-transmission-time");
        String transmissionSig = headers.get("paypal-transmission-sig");
        String certUrl = headers.get("paypal-cert-url");
        String authAlgo = headers.get("paypal-auth-algo");

        // 如果有Header没有传全，跳过验签
        if (transmissionId == null || transmissionTime == null || transmissionSig == null
                || certUrl == null || authAlgo == null) {
            log.warn("Missing PayPal webhook headers, skipping verification");
            return true;
        }

        try {
            String token = getAccessToken();
            HttpHeaders reqHeaders = new HttpHeaders();
            reqHeaders.setBearerAuth(token);
            reqHeaders.setContentType(MediaType.APPLICATION_JSON);

            String verifyPayload = String.format(
                    "{\"auth_algo\":\"%s\",\"cert_url\":\"%s\",\"transmission_id\":\"%s\"," +
                            "\"transmission_sig\":\"%s\",\"transmission_time\":\"%s\"," +
                            "\"webhook_id\":\"%s\",\"webhook_event\":%s}",
                    escape(authAlgo), escape(certUrl), escape(transmissionId),
                    escape(transmissionSig), escape(transmissionTime),
                    escape(webhookId), rawBody);

            log.debug("[EXECUTOR] Webhook signature verification request: transmissionId={}", transmissionId);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/v1/notifications/verify-webhook-signature",
                    HttpMethod.POST,
                    new HttpEntity<>(verifyPayload, reqHeaders),
                    Map.class);

            Map<String, Object> result = resp.getBody();
            String verificationStatus = (String) result.get("verification_status");
            log.info("[EXECUTOR] Webhook signature verification result: statusCode={}, verificationStatus={}",
                    resp.getStatusCode(), verificationStatus);
            return "SUCCESS".equals(verificationStatus);
        } catch (Exception e) {
            log.error("Webhook signature verification failed", e);
            return false;
        }
    }

    private static String parseEventType(String rawBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> event = mapper.readValue(rawBody, Map.class);
            return Optional.ofNullable(event.get("event_type"))
                    .map(Object::toString)
                    .orElse("UNKNOWN");
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * 从 Webhook event body 提取内部订单号。
     * custom_id 可能在 resource.custom_id（capture 事件）
     * 或 resource.purchase_units[0].custom_id（checkout-order 事件）。
     * 该字段在创建订单时由 buildCreateOrderPayload 写入。
     */
    private static String parseCustomId(String rawBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> event = mapper.readValue(rawBody, Map.class);
            Map<String, Object> resource = (Map<String, Object>) event.get("resource");
            if (resource != null) {
                Object customId = resource.get("custom_id");
                if (customId != null) return customId.toString();
                java.util.List<Map<String, Object>> units =
                        (java.util.List<Map<String, Object>>) resource.get("purchase_units");
                if (units != null && !units.isEmpty()) {
                    Object unitCustomId = units.get(0).get("custom_id");
                    if (unitCustomId != null) return unitCustomId.toString();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse custom_id from webhook", e);
        }
        return "";
    }

    /**
     * 从 Webhook event body 提取 resource.id（capture 交易 ID）。
     */
    private static String parseWebhookResourceId(String rawBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> event = mapper.readValue(rawBody, Map.class);
            Map<String, Object> resource = (Map<String, Object>) event.get("resource");
            if (resource != null) {
                Object id = resource.get("id");
                return id != null ? id.toString() : "";
            }
        } catch (Exception e) {
            log.warn("Failed to parse resource id from webhook", e);
        }
        return "";
    }

    /**
     * 从 Webhook event body 中 resource.links 提取 PayPal order ID。
     * Capture 事件的 links 里有一个 "rel": "up" 指向对应的 order。
     * URL 格式：.../v2/checkout/orders/ORDER_ID
     */
    private static String parsePayPalOrderId(String rawBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> event = mapper.readValue(rawBody, Map.class);
            Map<String, Object> resource = (Map<String, Object>) event.get("resource");
            if (resource == null) return "";

            Object linksObj = resource.get("links");
            if (linksObj instanceof java.util.List) {
                for (Object item : (java.util.List) linksObj) {
                    if (item instanceof Map) {
                        Map<?, ?> link = (Map<?, ?>) item;
                        if ("up".equals(link.get("rel"))) {
                            String href = (String) link.get("href");
                            if (href != null) {
                                // href = "https://.../v2/checkout/orders/ORDER_ID"
                                int idx = href.lastIndexOf('/');
                                return idx >= 0 ? href.substring(idx + 1) : href;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse paypal order id from webhook", e);
        }
        return "";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
