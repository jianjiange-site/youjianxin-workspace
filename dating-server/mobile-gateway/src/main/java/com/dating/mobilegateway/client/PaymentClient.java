package com.dating.mobilegateway.client;

import com.dating.youjianxin.proto.payment.AddCoinsRequest;
import com.dating.youjianxin.proto.payment.AddCoinsResponse;
import com.dating.youjianxin.proto.payment.BindAccountRequest;
import com.dating.youjianxin.proto.payment.BindAccountResponse;
import com.dating.youjianxin.proto.payment.CoinServiceGrpc;
import com.dating.youjianxin.proto.payment.ConsumeCoinsRequest;
import com.dating.youjianxin.proto.payment.ConsumeCoinsResponse;
import com.dating.youjianxin.proto.payment.CreateOrderRequest;
import com.dating.youjianxin.proto.payment.CreateOrderResponse;
import com.dating.youjianxin.proto.payment.GetBalanceRequest;
import com.dating.youjianxin.proto.payment.GetBalanceResponse;
import com.dating.youjianxin.proto.payment.GetCoinLedgerRequest;
import com.dating.youjianxin.proto.payment.GetCoinLedgerResponse;
import com.dating.youjianxin.proto.payment.GetCoinsRequest;
import com.dating.youjianxin.proto.payment.GetCoinsResponse;
import com.dating.youjianxin.proto.payment.GetHistoryRequest;
import com.dating.youjianxin.proto.payment.GetHistoryResponse;
import com.dating.youjianxin.proto.payment.GetProductsRequest;
import com.dating.youjianxin.proto.payment.GetProductsResponse;
import com.dating.youjianxin.proto.payment.GetSubscriptionRequest;
import com.dating.youjianxin.proto.payment.GetSubscriptionResponse;
import com.dating.youjianxin.proto.payment.PaymentMethod;
import com.dating.youjianxin.proto.payment.PaymentServiceGrpc;
import com.dating.youjianxin.proto.payment.PaymentVerifyRequest;
import com.dating.youjianxin.proto.payment.PaymentVerifyResponse;
import com.dating.youjianxin.proto.payment.WithdrawAccountType;
import com.dating.youjianxin.proto.payment.WithdrawRequest;
import com.dating.youjianxin.proto.payment.WithdrawResponse;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentClient {

    private static final long CALL_TIMEOUT_MS = 5000L;

    @GrpcClient("payment-service")
    private PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub;

    @GrpcClient("payment-service")
    private CoinServiceGrpc.CoinServiceBlockingStub coinStub;

    // ─────────────────── PaymentService ───────────────────

    public GetProductsResponse getProducts() {
        try {
            return paymentStub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getProducts(GetProductsRequest.newBuilder().build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public GetBalanceResponse getBalance() {
        try {
            return paymentStub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getBalance(GetBalanceRequest.newBuilder().build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public CreateOrderResponse createOrder(String productId, PaymentMethod paymentMethod,
                                           String currency, String platform, long userId) {
        try {
            return paymentStub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .createOrder(CreateOrderRequest.newBuilder()
                            .setProductId(productId)
                            .setPaymentMethod(paymentMethod)
                            .setCurrency(currency)
                            .setPlatform(platform)
                            .setUserId(userId)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public PaymentVerifyResponse verifyPayment(String orderId, String receiptData,
                                               String signature, String extOrderId,
                                               int paymentMethodValue) {
        try {
            return paymentStub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .verifyPayment(PaymentVerifyRequest.newBuilder()
                            .setOrderId(orderId)
                            .setReceiptData(receiptData)
                            .setSignature(signature)
                            .setExtOrderId(extOrderId)
                            .setPaymentMethodValue(paymentMethodValue)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public GetSubscriptionResponse getSubscription(long userId) {
        try {
            return paymentStub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getSubscription(GetSubscriptionRequest.newBuilder()
                            .setUserId(userId)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public BindAccountResponse bindWithdrawAccount(WithdrawAccountType type,
                                                    String accountIdentifier,
                                                    String holderName) {
        try {
            return paymentStub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .bindWithdrawAccount(BindAccountRequest.newBuilder()
                            .setType(type)
                            .setAccountIdentifier(accountIdentifier)
                            .setHolderName(holderName)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public WithdrawResponse withdraw(long amountCent, String accountId, String idempotencyKey) {
        try {
            return paymentStub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .withdraw(WithdrawRequest.newBuilder()
                            .setAmountCent(amountCent)
                            .setAccountId(accountId)
                            .setIdempotencyKey(idempotencyKey)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public GetHistoryResponse getHistory(int page, int size) {
        try {
            return paymentStub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getHistory(GetHistoryRequest.newBuilder()
                            .setPage(page)
                            .setSize(size)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    // ─────────────────── CoinService ───────────────────

    public GetCoinsResponse getCoins(long userId) {
        try {
            return coinStub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getCoins(GetCoinsRequest.newBuilder()
                            .setUserId(userId)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public GetCoinLedgerResponse getCoinLedger(long userId, int page, int size) {
        try {
            return coinStub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getCoinLedger(GetCoinLedgerRequest.newBuilder()
                            .setUserId(userId)
                            .setPage(page)
                            .setSize(size)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }
}
