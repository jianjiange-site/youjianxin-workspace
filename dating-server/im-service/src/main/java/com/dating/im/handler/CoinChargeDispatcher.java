package com.dating.im.handler;

import com.dating.im.client.PaymentGrpcClient;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * before-send 付费聊天的「异步扣币」执行器。
 *
 * <p>为什么异步:扣币是 payment-service 的带事务 DB 写,放在 OpenIM before-send 同步回调热路径上
 * (5s 预算、超时即拦消息)会因冷连接 / 锁竞争 / GC 击穿 deadline。本组件把扣减挪出回调线程:
 * before-send 只做「读余额准入」(够即放行),真正扣减提交到这里异步执行,回调线程立即返回。
 *
 * <p>语义:**准入 + 尽力扣**。放行后异步扣若失败(余额被并发扣穿 / payment 故障 / 进程重启丢队列任务),
 * 消息已发出无法回收 → 记 ERROR + 计数告警,接受少量漏扣;并发预检都通过时也可能轻微超发。
 * 详见 {@code docs/im-before-send-charge-anti-funnel.md}。幂等键 {@code im-msg:<messageId>} 不变,重试安全。
 */
@Component
public class CoinChargeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CoinChargeDispatcher.class);

    /** 异步扣币失败计数(reason 维度:insufficient / failed),监控告警口子。 */
    private static final String METRIC_ASYNC_FAIL = "im_before_send_charge_async_fail_total";
    /** 暂态故障(FAILED)的重试次数;consumeCoins 幂等,重试安全。 */
    private static final int MAX_RETRY = 1;

    private final PaymentGrpcClient paymentClient;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor;

    public CoinChargeDispatcher(PaymentGrpcClient paymentClient, MeterRegistry meterRegistry) {
        this.paymentClient = paymentClient;
        this.meterRegistry = meterRegistry;
        // core/max 2、有界队列 512;满则由提交线程(回调线程)兜底执行,绝不丢任务。
        this.executor = new ThreadPoolExecutor(
                2, 2, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(512),
                r -> {
                    Thread t = new Thread(r, "coin-charge-dispatch");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 异步扣币(在 before-send 准入放行后调用)。幂等键 = {@code im-msg:<messageId>}。
     */
    public void chargeAsync(long senderId, long cost, String reason, String messageId) {
        String idempotencyKey = "im-msg:" + messageId;
        executor.execute(() -> doCharge(senderId, cost, reason, messageId, idempotencyKey));
    }

    private void doCharge(long senderId, long cost, String reason, String messageId, String idempotencyKey) {
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            PaymentGrpcClient.ConsumeStatus status =
                    paymentClient.consumeCoins(senderId, cost, reason, idempotencyKey);
            switch (status) {
                case OK:
                    return;
                case INSUFFICIENT:
                    // 消息已放行却扣不到币(并发扣穿):重试无意义,记账告警,接受漏扣
                    log.error("[CHARGE_ASYNC_FAIL] insufficient after allow: msgId={} from={} cost={}",
                            messageId, senderId, cost);
                    meterRegistry.counter(METRIC_ASYNC_FAIL, "reason", "insufficient").increment();
                    return;
                case FAILED:
                default:
                    if (attempt < MAX_RETRY) {
                        continue; // 暂态故障,幂等重试
                    }
                    log.error("[CHARGE_ASYNC_FAIL] payment unavailable after allow: msgId={} from={} cost={}",
                            messageId, senderId, cost);
                    meterRegistry.counter(METRIC_ASYNC_FAIL, "reason", "failed").increment();
                    return;
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
