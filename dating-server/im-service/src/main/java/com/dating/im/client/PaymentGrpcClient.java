package com.dating.im.client;

import com.dating.youjianxin.proto.payment.CoinServiceGrpc;
import com.dating.youjianxin.proto.payment.ConsumeCoinsRequest;
import com.dating.youjianxin.proto.payment.ConsumeCoinsResponse;
import com.dating.youjianxin.proto.payment.GetCoinsRequest;
import com.dating.youjianxin.proto.payment.GetCoinsResponse;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * gRPC client for payment-service {@code CoinService.ConsumeCoins} — coin consumption with idempotency.
 *
 * <p>im-service 的 handler 约定不抛 {@code BizException}(无该基础设施),故此处把三种结局映射成
 * {@link ConsumeStatus} 返回,由调用方决定放行 / 拒发:
 * <ul>
 *   <li>{@code OK}           — 扣币成功(payment {@code code == 0})</li>
 *   <li>{@code INSUFFICIENT} — 余额不足(payment {@code code == 3001})</li>
 *   <li>{@code FAILED}       — RPC 故障 / 超时 / 其他非 0 业务码(已 log.error)</li>
 * </ul>
 */
@Component
public class PaymentGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGrpcClient.class);

    private static final long CALL_TIMEOUT_MS = 2000L;
    /** 只读余额预检的 deadline:读路径无事务/无锁,给短超时,远小于 OpenIM 5s 回调预算。 */
    private static final long READ_TIMEOUT_MS = 800L;
    /** payment-service CoinService 余额不足返回码。 */
    private static final int CODE_INSUFFICIENT_COINS = 3001;

    public enum ConsumeStatus {
        OK,
        INSUFFICIENT,
        FAILED
    }

    @GrpcClient("payment-service")
    private CoinServiceGrpc.CoinServiceBlockingStub coinStub;

    /**
     * 扣金币(幂等 by {@code idempotencyKey}:同一 (userId, key) 只扣一次,重复调用幂等返回)。
     *
     * @return {@link ConsumeStatus};永不抛异常,RPC 故障归类为 {@code FAILED}
     */
    public ConsumeStatus consumeCoins(long userId, long amount, String reason, String idempotencyKey) {
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
            if (code == 0) {
                log.info("consumeCoins ok: userId={} amount={} balance={} key={}",
                        userId, amount, resp.getBalance(), idempotencyKey);
                return ConsumeStatus.OK;
            }
            if (code == CODE_INSUFFICIENT_COINS) {
                log.info("consumeCoins insufficient: userId={} amount={} balance={}",
                        userId, amount, resp.getBalance());
                return ConsumeStatus.INSUFFICIENT;
            }
            log.error("consumeCoins non-zero code: userId={} amount={} code={} msg={}",
                    userId, amount, code, resp.getBase().getMessage());
            return ConsumeStatus.FAILED;
        } catch (StatusRuntimeException sre) {
            log.error("consumeCoins RPC failed: userId={} amount={} key={} status={}",
                    userId, amount, idempotencyKey, sre.getStatus(), sre);
            return ConsumeStatus.FAILED;
        }
    }

    /**
     * 只读预检:查用户可用金币余额(free + paid 合计),用于 before-send 同步准入。
     *
     * <p>不扣减、无事务,比 {@link #consumeCoins} 稳;沿用「永不抛、归类返回」风格:RPC 故障 /
     * 非 0 业务码归为 {@link BalanceResult#failed()},由调用方决定 fail-close。
     */
    public BalanceResult getBalance(long userId) {
        try {
            GetCoinsResponse resp = coinStub
                    .withDeadlineAfter(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getCoins(GetCoinsRequest.newBuilder().setUserId(userId).build());
            int code = resp.getBase().getCode();
            if (code == 0) {
                return BalanceResult.ok(resp.getBalance());
            }
            log.error("getBalance non-zero code: userId={} code={} msg={}",
                    userId, code, resp.getBase().getMessage());
            return BalanceResult.failed();
        } catch (StatusRuntimeException sre) {
            log.error("getBalance RPC failed: userId={} status={}", userId, sre.getStatus(), sre);
            return BalanceResult.failed();
        }
    }

    /** 余额预检结果:{@code ok=false} 表示读失败(RPC 故障 / 非 0 码),调用方据此 fail-close。 */
    public record BalanceResult(boolean ok, long balance) {
        public static BalanceResult ok(long balance) {
            return new BalanceResult(true, balance);
        }

        public static BalanceResult failed() {
            return new BalanceResult(false, 0L);
        }
    }
}
