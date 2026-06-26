package com.dating.im.handler;

import com.dating.im.notification.NotificationService;
import com.dating.im.notification.payload.TypingPayload;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在 DH(AI 数字人)生成回复期间,以 DH 名义向 BH 持续下发「正在输入」(OpenIM business-notification,key=typing)。
 *
 * <p>通道选择:OpenIM REST {@code /msg/send_msg} 拒收 typing(contentType=113,errCode=1001 ArgsError),
 * 故改走 {@link NotificationService} 的 business-notification(客户端经 {@code onRecvCustomBusinessMessage} 收),
 * 与 BH↔BH 的 input-states 通道并存(每个会话只走其一)。
 *
 * <p>为什么要「持续」下发:typing 是 online-only 瞬时信号,客户端侧有兜底超时(5s)会自动收起,
 * 故 AI 生成耗时若超过该超时,需周期性续发续命;{@link #refreshIntervalMs} 默认 3s(< 客户端 5s)。
 *
 * <p>用法(包住阻塞的生成调用,异常/超时也保证停发):
 * <pre>{@code
 * try (DhTypingEmitter.Handle ignored = dhTypingEmitter.start(dhUserId, bhUserId)) {
 *     aiReply = aiChatClient.chat(...);
 * }
 * }</pre>
 *
 * <p>全程 fire-and-forget:任何 typing 下发失败只记日志,绝不影响 AI 回复主流程。
 */
@Component
public class DhTypingEmitter {

    private static final Logger log = LoggerFactory.getLogger(DhTypingEmitter.class);

    /**
     * 一次 typing 窗口的句柄。{@link AutoCloseable} 但 {@link #close()} 不抛受检异常,
     * 故 try-with-resources 无需 catch。close() 时停止续发并下发一次「停止」。
     */
    @FunctionalInterface
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    private static final Handle NOOP = () -> { };

    private final NotificationService notificationService;
    private final boolean enabled;
    private final long refreshIntervalMs;
    private final long onsetMinMs;
    private final long onsetMaxMs;
    private final ScheduledExecutorService scheduler;

    public DhTypingEmitter(NotificationService notificationService,
                           @Value("${im.typing.enabled:true}") boolean enabled,
                           @Value("${im.typing.refresh-interval-ms:3000}") long refreshIntervalMs,
                           @Value("${im.typing.onset-delay-min-ms:2000}") long onsetMinMs,
                           @Value("${im.typing.onset-delay-max-ms:5000}") long onsetMaxMs,
                           @Value("${im.typing.scheduler-threads:4}") int schedulerThreads) {
        this.notificationService = notificationService;
        this.enabled = enabled;
        this.refreshIntervalMs = refreshIntervalMs;
        this.onsetMinMs = onsetMinMs;
        this.onsetMaxMs = onsetMaxMs;
        this.scheduler = Executors.newScheduledThreadPool(schedulerThreads, r -> {
            Thread t = new Thread(r, "dh-typing");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 开始向 {@code bhUserId} 持续下发 {@code dhUserId} 的「正在输入」。
     * 为拟真,首帧延迟一个随机「读取时间」(`onset-delay-min/max-ms`,默认 2-5s)再发,
     * 之后每 {@link #refreshIntervalMs} 续发,直到返回句柄被 {@link Handle#close() close}。
     *
     * @param dhUserId 输入方(DH)OpenIM userID
     * @param bhUserId 接收方(BH)OpenIM userID
     * @return 关闭即停发并下发停止信号的句柄;{@code im.typing.enabled=false} 时返回 no-op
     */
    public Handle start(String dhUserId, String bhUserId) {
        if (!enabled) {
            return NOOP;
        }
        long onsetMs = randomOnsetMs();
        // started 守卫:若 AI 生成比随机读取延迟还快(首帧尚未发就 close),则不发多余的 stop。
        AtomicBoolean started = new AtomicBoolean(false);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> {
                    started.set(true);
                    safeSend(dhUserId, bhUserId, true);
                },
                onsetMs, refreshIntervalMs, TimeUnit.MILLISECONDS);
        return () -> {
            future.cancel(false);
            if (started.get()) {
                safeSend(dhUserId, bhUserId, false);
            }
        };
    }

    /**
     * 单次下发一帧「正在输入」。用于多条回复的间隙(真人「打一条、再打下一条」):
     * 间隙 ≤ 客户端兜底超时,该段消息到达后客户端自行收起,故无需配套 stop。
     */
    public void ping(String dhUserId, String bhUserId) {
        if (enabled) {
            safeSend(dhUserId, bhUserId, true);
        }
    }

    /** 随机读取延迟 ∈ [onsetMinMs, onsetMaxMs];区间非法时退化为 min。 */
    private long randomOnsetMs() {
        return onsetMaxMs <= onsetMinMs
                ? onsetMinMs
                : ThreadLocalRandom.current().nextLong(onsetMinMs, onsetMaxMs + 1);
    }

    private void safeSend(String dhUserId, String bhUserId, boolean focus) {
        try {
            // 客户端按 payload.fromUserId 过滤会话,conversationId 仅信息性,这里给个可读值。
            String conversationId = dhUserId + "_" + bhUserId;
            TypingPayload payload = focus
                    ? TypingPayload.start(dhUserId, conversationId)
                    : TypingPayload.stop(dhUserId, conversationId);
            notificationService.send(bhUserId, payload);
        } catch (Exception e) {
            log.warn("DH typing send error: dh={} bh={} focus={}", dhUserId, bhUserId, focus, e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}

