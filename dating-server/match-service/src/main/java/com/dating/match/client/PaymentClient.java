package com.dating.match.client;

import com.dating.match.exception.BizException;
import com.dating.match.exception.ErrorCodes;
import com.dating.youjianxin.proto.payment.ConsumeCoinsRequest;
import com.dating.youjianxin.proto.payment.ConsumeCoinsResponse;
import com.dating.youjianxin.proto.payment.CoinServiceGrpc;
import com.dating.youjianxin.proto.payment.GetCoinsRequest;
import com.dating.youjianxin.proto.payment.GetCoinsResponse;
import com.dating.youjianxin.proto.payment.GetSubscriptionRequest;
import com.dating.youjianxin.proto.payment.GetSubscriptionResponse;
import com.dating.youjianxin.proto.payment.PaymentServiceGrpc;
import com.dating.youjianxin.proto.payment.SubscriptionInfo;
import com.dating.youjianxin.proto.payment.SubscriptionTier;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * payment-service 客户端封装。
 *
 * <p>用途:
 * <ul>
 *   <li>{@link #getSubscription}:配额判定的档位来源</li>
 *   <li>{@link #consumeCoinsIdempotent}:SuperHi 扣 100 金币(用 client_request_id 幂等)</li>
 *   <li>{@link #getCoinBalance}:SuperHi 前余额预检</li>
 * </ul>
 *
 * <p>注:GetSubscription 5min cache 留待后续优化,Phase 3 直接 RPC。
 */
@Slf4j
@Component
public class PaymentClient {

    private static final long CALL_TIMEOUT_MS = 2000L;

    @GrpcClient("payment-service")
    private PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub;

    @GrpcClient("payment-service")
    private CoinServiceGrpc.CoinServiceBlockingStub coinStub;

    public SubscriptionTier getSubscription(long userId) {
        try {
            GetSubscriptionResponse resp = paymentStub
                    .withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getSubscription(GetSubscriptionRequest.newBuilder().setUserId(userId).build());
            if (resp.getBase().getCode() != 0) {
                log.warn("getSubscription non-zero code: userId={} code={} msg={}",
                        userId, resp.getBase().getCode(), resp.getBase().getMessage());
                return SubscriptionTier.SUBSCRIPTION_TIER_FREE;
            }
            SubscriptionInfo info = resp.getSubscription();
            if (info.getIsActive()) {
                return info.getTier();
            }
            return SubscriptionTier.SUBSCRIPTION_TIER_FREE;
        } catch (StatusRuntimeException sre) {
            log.error("getSubscription failed userId={} status={}", userId, sre.getStatus(), sre);
            // 降级 FREE,不阻塞 swipe(代价是订阅用户被错算成 FREE 一段时间)
            return SubscriptionTier.SUBSCRIPTION_TIER_FREE;
        }
    }

    public long getCoinBalance(long userId) {
        try {
            GetCoinsResponse resp = coinStub
                    .withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getCoins(GetCoinsRequest.newBuilder().setUserId(userId).build());
            return resp.getBase().getCode() == 0 ? resp.getBalance() : 0L;
        } catch (StatusRuntimeException sre) {
            log.error("getCoins failed userId={} status={}", userId, sre.getStatus(), sre);
            throw new BizException(ErrorCodes.PAYMENT_RPC_FAILED, "payment-service unavailable");
        }
    }

    /**
     * 扣金币(幂等 by idempotencyKey)。
     * @return 操作后余额;余额不足时抛 BizException(INSUFFICIENT_COINS)
     */
    public long consumeCoinsIdempotent(long userId, long amount, String reason, String idempotencyKey) {
        try {
            ConsumeCoinsResponse resp = coinStub
                    .withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .consumeCoins(ConsumeCoinsRequest.newBuilder()
                            .setUserId(userId)
                            .setAmount(amount)
                            .setReason(reason == null ? "" : reason)
                            .setIdempotencyKey(idempotencyKey == null ? "" : idempotencyKey)
                            .build());
            int code = resp.getBase().getCode();
            if (code == 0) return resp.getBalance();
            if (code == 3001) throw new BizException(ErrorCodes.INSUFFICIENT_COINS, resp.getBase().getMessage());
            throw new BizException(ErrorCodes.PAYMENT_RPC_FAILED,
                    "consumeCoins code=" + code + " msg=" + resp.getBase().getMessage());
        } catch (StatusRuntimeException sre) {
            log.error("consumeCoins failed userId={} amount={} key={} status={}",
                    userId, amount, idempotencyKey, sre.getStatus(), sre);
            throw new BizException(ErrorCodes.PAYMENT_RPC_FAILED, "payment-service unavailable");
        }
    }
}
