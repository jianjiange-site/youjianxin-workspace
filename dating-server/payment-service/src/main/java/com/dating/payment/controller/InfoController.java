package com.dating.payment.controller;

import com.dating.payment.grpc.GrpcAdapter;
import com.dating.payment.grpc.PaymentGrpcService;
import com.dating.youjianxin.proto.payment.GetBalanceRequest;
import com.dating.youjianxin.proto.payment.GetBalanceResponse;
import com.dating.youjianxin.proto.payment.GetProductsRequest;
import com.dating.youjianxin.proto.payment.GetProductsResponse;
import com.dating.youjianxin.proto.payment.GetSubscriptionRequest;
import com.dating.youjianxin.proto.payment.GetSubscriptionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class InfoController {

    private final PaymentGrpcService paymentGrpcService;

    public InfoController(PaymentGrpcService paymentGrpcService) {
        this.paymentGrpcService = paymentGrpcService;
    }

    @GetMapping("/products")
    public ResponseEntity<GetProductsResponse> getProducts(
            @RequestParam(defaultValue = "ios") String platform) {
        return ResponseEntity.ok(GrpcAdapter.invoke(obs ->
                paymentGrpcService.getProducts(
                        GetProductsRequest.newBuilder().setPlatform(platform).build(), obs)));
    }

    @GetMapping("/balance")
    public ResponseEntity<GetBalanceResponse> getBalance() {
        return ResponseEntity.ok(GrpcAdapter.invoke(obs ->
                paymentGrpcService.getBalance(GetBalanceRequest.newBuilder().build(), obs)));
    }

    @PostMapping("/subscription")
    public ResponseEntity<GetSubscriptionResponse> getSubscription(@RequestBody Map<String, Object> body) {
        long userId = toLong(body.get("user_id"));
        return ResponseEntity.ok(GrpcAdapter.invoke(obs ->
                paymentGrpcService.getSubscription(
                        GetSubscriptionRequest.newBuilder().setUserId(userId).build(), obs)));
    }

    private static long toLong(Object val) {
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) return Long.parseLong((String) val);
        return 0L;
    }
}
