package com.dating.payment.controller;

import com.dating.payment.grpc.GrpcAdapter;
import com.dating.payment.grpc.PaymentGrpcService;
import com.dating.payment.service.PaymentService;
import com.dating.youjianxin.proto.payment.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentGrpcService paymentGrpcService;
    private final PaymentService paymentService;

    public PaymentController(PaymentGrpcService paymentGrpcService, PaymentService paymentService) {
        this.paymentGrpcService = paymentGrpcService;
        this.paymentService = paymentService;
    }

    @PostMapping("/payments/orders")
    public ResponseEntity<CreateOrderResponse> createOrder(@RequestBody Map<String, Object> body) {
        log.info("[PAYMENT] createOrder request body: {}", body);
        long userId = toLong(body.get("user_id"));
        if (userId <= 0) {
            CreateOrderResponse error = CreateOrderResponse.newBuilder()
                    .setBase(BaseResponse.newBuilder().setCode(4001).setMessage("user_id is required"))
                    .build();
            return ResponseEntity.badRequest().body(error);
        }
        String productId = (String) body.getOrDefault("product_id", "");
        String paymentMethodStr = (String) body.getOrDefault("payment_method", "APPLE_PAY");
        String currency = (String) body.getOrDefault("currency", "USD");
        String platform = (String) body.getOrDefault("platform", "ios");
        String returnUrl = (String) body.getOrDefault("return_url", "");
        String cancelUrl = (String) body.getOrDefault("cancel_url", "");

        PaymentMethod paymentMethod;
        try {
            paymentMethod = PaymentMethod.valueOf(paymentMethodStr);
        } catch (IllegalArgumentException e) {
            paymentMethod = PaymentMethod.APPLE_PAY;
        }

        CreateOrderRequest request = CreateOrderRequest.newBuilder()
                .setProductId(productId)
                .setPaymentMethod(paymentMethod)
                .setCurrency(currency)
                .setPlatform(platform)
                .build();
        // returnUrl/cancelUrl 是 Web 特有参数，proto 未定义，直接调 service
        CreateOrderResponse response = paymentService.createOrder(userId, request, returnUrl, cancelUrl);
        log.info("[PAYMENT] createOrder response: {}", response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/payments/verify")
    public ResponseEntity<PaymentVerifyResponse> verifyPayment(@RequestBody Map<String, Object> body) {
        log.info("[PAYMENT] verifyPayment request body: {}", body);
        String orderId = (String) body.getOrDefault("order_id", "");
        String receiptData = (String) body.getOrDefault("receipt_data", "");
        String extOrderId = (String) body.getOrDefault("ext_order_id", "");
        String paymentMethodStr = (String) body.getOrDefault("payment_method", "");

        PaymentVerifyRequest.Builder builder = PaymentVerifyRequest.newBuilder()
                .setOrderId(orderId)
                .setReceiptData(receiptData);
        if (body.containsKey("signature")) {
            builder.setSignature((String) body.get("signature"));
        }
        if (!extOrderId.isEmpty()) {
            builder.setExtOrderId(extOrderId);
        }
        if (!paymentMethodStr.isEmpty()) {
            try {
                builder.setPaymentMethod(PaymentMethod.valueOf(paymentMethodStr));
            } catch (IllegalArgumentException e) {
                log.warn("[PAYMENT] invalid payment_method: {}", paymentMethodStr);
            }
        }

        PaymentVerifyResponse response = GrpcAdapter.invoke(obs ->
                paymentGrpcService.verifyPayment(builder.build(), obs));
        log.info("[PAYMENT] verifyPayment response: {}", response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/payments/webhook")
    public ResponseEntity<WebhookResponse> handleWebhook(@RequestBody Map<String, Object> body,
                                                         @RequestHeader Map<String, String> headers) {
        String channel = (String) body.getOrDefault("channel", "");
        String eventType = (String) body.getOrDefault("event_type", "");

        WebhookRequest request = WebhookRequest.newBuilder()
                .setChannel(channel)
                .setEventType(eventType)
                .putAllHeaders(headers)
                .build();
        return ResponseEntity.ok(GrpcAdapter.invoke(obs ->
                paymentGrpcService.handleWebhook(request, obs)));
    }

    @PostMapping("/payments/webhook/paypal")
    public ResponseEntity<WebhookResponse> handlePayPalWebhook(
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers) {
        log.info("[PAYMENT] handlePayPalWebhook received, body={}, transmissionId={}",
                rawBody, headers.get("paypal-transmission-id"));
        // PayPal webhook 需要 raw body 做签名验证，gRPC proto 无此语义，直接调 service
        WebhookResponse response = paymentService.handlePayPalWebhook(rawBody, headers);
        log.info("[PAYMENT] handlePayPalWebhook response: {}", response);
        return ResponseEntity.ok(response);
    }

    private static long toLong(Object val) {
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) return Long.parseLong((String) val);
        return 0L;
    }
}
