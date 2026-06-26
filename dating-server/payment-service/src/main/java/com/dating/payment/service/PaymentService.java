package com.dating.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.payment.entity.PaymentOrder;
import com.dating.payment.executor.paypal.CaptureResult;
import com.dating.payment.executor.paypal.PaypalExecutor;
import com.dating.payment.mapper.PaymentOrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dating.youjianxin.proto.payment.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 支付核心业务：创建订单、票据校验、异步回调。
 *
 * <p>根据 PaymentMethod 将请求路由到对应的执行器（Executor），
 * 执行器封装与第三方支付渠道的具体通信逻辑。
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final PaypalExecutor paypalExecutor;
    private final CoinService coinService;
    private final PaymentOrderMapper paymentOrderMapper;
    private final InfoService infoService;
    private final SubscriptionService subscriptionService;

    public PaymentService(PaypalExecutor paypalExecutor, CoinService coinService,
                          PaymentOrderMapper paymentOrderMapper, InfoService infoService,
                          SubscriptionService subscriptionService) {
        this.paypalExecutor = paypalExecutor;
        this.coinService = coinService;
        this.paymentOrderMapper = paymentOrderMapper;
        this.infoService = infoService;
        this.subscriptionService = subscriptionService;
    }

    public CreateOrderResponse createOrder(long userId, CreateOrderRequest request,
                                           String returnUrl, String cancelUrl) {
        log.info("[SERVICE] createOrder: userId={}, {}", userId, request);

        if (request.getPaymentMethod() == PaymentMethod.PAYPAL) {
            CreateOrderResponse response = handlePayPalCreateOrder(userId, request, returnUrl, cancelUrl);
            log.info("[SERVICE] createOrder result: {}", response);
            return response;
        }

        log.warn("[SERVICE] Unsupported payment method: {}", request.getPaymentMethod());
        return CreateOrderResponse.newBuilder()
                .setBase(Responses.notImplemented())
                .build();
    }

    public WebhookResponse handlePayPalWebhook(String rawBody, Map<String, String> headers) {
        log.info("[SERVICE] handlePayPalWebhook: body={}", rawBody);
        String eventType = parseWebhookEventType(rawBody);
        String orderId = parseWebhookCustomId(rawBody);

        WebhookResponse response = paypalExecutor.handleWebhook(rawBody, headers);
        log.info("[SERVICE] handlePayPalWebhook result: {}", response);

        if ("PAYMENT.CAPTURE.COMPLETED".equals(eventType) && !orderId.isEmpty()) {
            advanceStatusToPaid(orderId);
            grantReward(orderId, "PayPal");
        }
        return response;
    }

    public PaymentVerifyResponse verifyPayment(PaymentVerifyRequest request) {
        log.info("[SERVICE] verifyPayment: {}", request);

        if (request.getPaymentMethod() == PaymentMethod.PAYPAL && !request.getExtOrderId().isEmpty()) {
            CaptureResult result = paypalExecutor.captureOrder(request.getExtOrderId());
            PaymentVerifyResponse.Builder builder = PaymentVerifyResponse.newBuilder();
            if (result.isCompleted()) {
                builder.setBase(BaseResponse.newBuilder().setCode(0).setMessage("OK"))
                        .setOrderId(request.getOrderId())
                        .setExtOrderId(request.getExtOrderId())
                        .setStatus(OrderStatus.PAID);
                String orderId = !request.getOrderId().isEmpty() ? request.getOrderId() : null;
                if (orderId != null) {
                    advanceStatusToPaid(orderId);
                    grantReward(orderId, "PayPal");
                }
            } else {
                builder.setBase(BaseResponse.newBuilder()
                                .setCode(2002)
                                .setMessage(result.getErrorMessage() != null ? result.getErrorMessage() : "Capture failed"))
                        .setOrderId(request.getOrderId())
                        .setExtOrderId(request.getExtOrderId())
                        .setStatus(OrderStatus.FAILED);
            }
            PaymentVerifyResponse response = builder.build();
            log.info("[SERVICE] verifyPayment result: {}", response);
            return response;
        }

        PaymentVerifyResponse response = PaymentVerifyResponse.newBuilder()
                .setBase(Responses.notImplemented())
                .setOrderId(request.getOrderId())
                .build();
        log.info("[SERVICE] verifyPayment result: {}", response);
        return response;
    }

    public WebhookResponse handleWebhook(WebhookRequest request) {
        log.warn("Generic webhook handler not implemented for channel: {}", request.getChannel());
        return WebhookResponse.newBuilder()
                .setBase(Responses.notImplemented())
                .build();
    }

    void grantReward(String orderId, String source) {
        PaymentOrder order = paymentOrderMapper.selectOne(
                new LambdaQueryWrapper<PaymentOrder>().eq(PaymentOrder::getOrderId, orderId));
        if (order == null) {
            log.warn("[SERVICE] grantReward: order not found, orderId={}", orderId);
            return;
        }
        if ("GRANTED".equals(order.getStatus())) {
            log.info("[SERVICE] grantReward: already granted, orderId={}", orderId);
            return;
        }

        String productId = order.getProductId();
        long userId = order.getUserId();

        // 订阅商品：先激活 / 续期订阅，再发付费金币
        if (infoService.isSubscriptionProduct(productId)) {
            short tier = infoService.getSubscriptionTier(productId);
            long durationDays = infoService.getSubscriptionDurationDays(productId);
            subscriptionService.activateSubscription(userId, tier, durationDays, source);
        }

        long coins = infoService.getCoinAmount(productId);
        String reason = source + ": " + infoService.getProductTitle(productId);
        AddCoinsRequest coinRequest = AddCoinsRequest.newBuilder()
                .setUserId(userId)
                .setAmount(coins)
                .setReason(reason)
                .build();
        coinService.addPaidCoins(coinRequest);

        order.setStatus("GRANTED");
        paymentOrderMapper.updateById(order);

        log.info("[SERVICE] grantReward: orderId={}, userId={}, coins={}", orderId, userId, coins);
    }

    private void advanceStatusToPaid(String orderId) {
        PaymentOrder order = paymentOrderMapper.selectOne(
                new LambdaQueryWrapper<PaymentOrder>().eq(PaymentOrder::getOrderId, orderId));
        if (order != null && "INIT".equals(order.getStatus())) {
            order.setStatus("PAID");
            paymentOrderMapper.updateById(order);
            log.info("[SERVICE] advanceStatusToPaid: orderId={}", orderId);
        }
    }

    private CreateOrderResponse handlePayPalCreateOrder(long userId, CreateOrderRequest request,
                                                         String returnUrl, String cancelUrl) {
        String orderId = generateOrderId(request.getProductId());
        long amountCent = infoService.getPriceCent(request.getProductId());
        log.info("[SERVICE] handlePayPalCreateOrder: orderId={}, amountCent={}, currency={}",
                orderId, amountCent, request.getCurrency());

        CreateOrderResponse response = paypalExecutor.createOrder(
                orderId, amountCent, request.getCurrency(),
                returnUrl, cancelUrl);
        log.info("[SERVICE] handlePayPalCreateOrder response: {}", response);

        if (userId > 0) {
            PaymentOrder order = new PaymentOrder();
            order.setUserId(userId);
            order.setOrderId(orderId);
            order.setProductId(request.getProductId());
            order.setAmount(BigDecimal.valueOf(amountCent).movePointLeft(2));
            order.setCurrency(request.getCurrency());
            order.setPaymentChannel("PAYPAL");
            order.setStatus("INIT");
            order.setRefundStatus("NONE");
            order.setRefundedAmount(BigDecimal.ZERO);
            order.setExtTransactionId(response.getExtOrderId());
            order.setNotifyStatus("PENDING");
            order.setNotifyCount(0);
            paymentOrderMapper.insert(order);
            log.info("[SERVICE] payment order saved: orderId={}, extOrderId={}, userId={}",
                    orderId, response.getExtOrderId(), userId);
        }

        return response;
    }

    private static String generateOrderId(String productId) {
        return "P" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6);
    }

    @SuppressWarnings("unchecked")
    private static String parseWebhookEventType(String rawBody) {
        try {
            Map<String, Object> event = objectMapper.readValue(rawBody, Map.class);
            return Optional.ofNullable(event.get("event_type")).map(Object::toString).orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static String parseWebhookCustomId(String rawBody) {
        try {
            Map<String, Object> event = objectMapper.readValue(rawBody, Map.class);
            Map<String, Object> resource = (Map<String, Object>) event.get("resource");
            if (resource != null) {
                Object customId = resource.get("custom_id");
                if (customId != null) return customId.toString();
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }
}
