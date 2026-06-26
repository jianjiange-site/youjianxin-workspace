package com.dating.im.handler;

import com.dating.im.model.ImMessage;
import com.dating.im.recorder.MessageRecorder;
import com.dating.im.sender.MessageSender;
import com.dating.im.util.SentenceSplitter;
import com.dating.youjianxin.proto.im.MessageType;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 把 AI 回复按句末标点切成多条,模拟真人「短句多条」的节奏分别发出。
 *
 * <p>为什么异步:{@link MessageSentHandler#handle} 跑在 OpenIM 回调的同步 gRPC 线程里(已阻塞在
 * ai-chat / vision 上),若再在该线程里 sleep 累加打字延迟,会拉长回调响应、可能触发网关/gRPC
 * 超时。因此分句 + 节奏化发送提交到本组件的小线程池异步执行;回调线程拿到 aiReply 后立即返回。
 * 单条回复的多条消息由**同一个任务顺序**发出,保证顺序与节奏。
 *
 * <p>messageId:单条沿用 {@code <原id>_ai};多条用 {@code <原id>_ai_1 .. _ai_N},各自唯一,
 * 满足 {@code chat_messages.message_id} 主键约束。
 */
@Component
public class AiReplyDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AiReplyDispatcher.class);

    private final List<MessageSender> senders;
    private final MessageRecorder recorder;
    private final DhTypingEmitter dhTypingEmitter;

    /** 最多切成几条,超出并入最后一条。 */
    private final int maxMessages;
    /** 切分句数达到该值才考虑分段发(须与 minSplitChars 同时满足);否则合并成一条。 */
    private final int minSegments;
    /** 回复总字数达到该值才考虑分段发(须与 minSegments 同时满足);否则合并成一条。 */
    private final int minSplitChars;
    /** 每字符延迟(ms),按本条字数线性放大,模拟打字。 */
    private final int perCharDelayMs;
    /** 单条延迟下限 / 上限(ms)。 */
    private final int minDelayMs;
    private final int maxDelayMs;

    private final ExecutorService executor;

    public AiReplyDispatcher(List<MessageSender> senders,
                             MessageRecorder recorder,
                             DhTypingEmitter dhTypingEmitter,
                             @Value("${im.ai-reply.max-messages:4}") int maxMessages,
                             @Value("${im.ai-reply.min-segments:3}") int minSegments,
                             @Value("${im.ai-reply.min-split-chars:30}") int minSplitChars,
                             @Value("${im.ai-reply.per-char-delay-ms:45}") int perCharDelayMs,
                             @Value("${im.ai-reply.min-delay-ms:300}") int minDelayMs,
                             @Value("${im.ai-reply.max-delay-ms:1500}") int maxDelayMs) {
        this.senders = senders;
        this.recorder = recorder;
        this.dhTypingEmitter = dhTypingEmitter;
        this.maxMessages = maxMessages;
        this.minSegments = minSegments;
        this.minSplitChars = minSplitChars;
        this.perCharDelayMs = perCharDelayMs;
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        // core/max 4、有界队列;满则由提交线程(回调线程)兜底执行,绝不丢消息。
        this.executor = new ThreadPoolExecutor(
                4, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "ai-reply-dispatch");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 异步:把 aiReply 切句并按节奏分多条发给原消息的发送方(DH→BH)。
     *
     * @param original BH→DH 的原始入站消息
     * @param aiReply  ai-chat 返回的完整文本
     */
    public void dispatch(ImMessage original, String aiReply) {
        List<String> parts = SentenceSplitter.split(aiReply, maxMessages);
        if (parts.isEmpty()) {
            return;
        }
        // 「真人感」收敛:句数与总字数都达标才分多条发;否则合并成一条,避免短回复被硬切显得机械。
        List<String> toSend = shouldSegment(parts) ? parts : List.of(String.join(" ", parts));
        executor.execute(() -> sendSequentially(original, toSend));
    }

    /** 句数(≥minSegments)与总字数(≥minSplitChars)都达标才分段发;否则合并成一条。 */
    private boolean shouldSegment(List<String> parts) {
        if (parts.size() < minSegments) {
            return false;
        }
        int totalChars = 0;
        for (String p : parts) {
            totalChars += p.length();
        }
        return totalChars >= minSplitChars;
    }

    private void sendSequentially(ImMessage original, List<String> parts) {
        int n = parts.size();
        for (int idx = 0; idx < n; idx++) {
            String part = parts.get(idx);
            if (idx > 0) {
                // 真人「打一条、再打下一条」:间隙先显示 typing,再按打字节奏等待。
                // gap ≤ max-delay-ms < 客户端兜底超时,该段消息到达后客户端自行收起,无需配套 stop。
                dhTypingEmitter.ping(/*dh*/ original.toUserId(), /*bh*/ original.fromUserId());
                if (!sleep(delayFor(part))) {
                    log.warn("AI reply dispatch interrupted, abort remaining: sent={}/{} baseMsgId={}",
                            idx, n, original.messageId());
                    return;
                }
            }

            String messageId = n == 1
                    ? original.messageId() + "_ai"
                    : original.messageId() + "_ai_" + (idx + 1);

            ImMessage reply = ImMessage.builder()
                    .messageId(messageId)
                    .fromUserId(original.toUserId())   // DH sends reply
                    .toUserId(original.fromUserId())   // to BH
                    .content(part)
                    .type(MessageType.TEXT)
                    .conversationType(original.conversationType())
                    .provider(original.provider())
                    .timestamp(System.currentTimeMillis() / 1000)
                    .build();

            boolean sent = sendReply(reply);
            log.info("AI reply sent={} msgId={} ({}/{})", sent, messageId, idx + 1, n);

            // Record the AI reply too (与现状一致:无论 sent 与否都记录)
            recorder.save(reply, "DH_BH");
        }
    }

    /** 按本条字数算延迟,clamp 到 [min, max]。 */
    private long delayFor(String part) {
        long d = (long) part.length() * perCharDelayMs;
        return Math.max(minDelayMs, Math.min(maxDelayMs, d));
    }

    /** @return false if interrupted */
    private boolean sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Selects the appropriate sender for the message's provider. */
    private boolean sendReply(ImMessage reply) {
        for (MessageSender sender : senders) {
            if (sender.supports(reply.provider())) {
                return sender.send(reply);
            }
        }
        log.warn("No MessageSender found for provider={}", reply.provider());
        return false;
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
