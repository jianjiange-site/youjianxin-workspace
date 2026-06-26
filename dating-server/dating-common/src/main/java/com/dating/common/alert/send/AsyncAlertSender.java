package com.dating.common.alert.send;

import com.dating.common.alert.AlertEvent;
import com.dating.common.alert.AlertProperties;
import com.dating.common.alert.SummaryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// 异步队列消费 + HTTP 调用。业务线程零阻塞:队列满直接计数丢弃。
// 防递归:doSend 内部任何异常仅 log.debug;消费线程 name 前缀固定,Appender 据此短路。
// SmartLifecycle phase = Integer.MAX_VALUE - 100,确保业务收尾时的 log.error 仍有机会发出去。
public class AsyncAlertSender implements AlertSender, SmartLifecycle {

    public static final String SENDER_THREAD_PREFIX = "dating-alert-sender-";

    private static final Logger log = LoggerFactory.getLogger(AsyncAlertSender.class);

    private final ThreadPoolExecutor executor;
    private final WeWorkMessageClient client;
    private final MessageRenderer renderer;
    private final long shutdownTimeoutMs;
    private final AtomicLong droppedCount = new AtomicLong();
    private volatile boolean running = false;

    public AsyncAlertSender(AlertProperties.Async cfg, WeWorkMessageClient client, MessageRenderer renderer) {
        this.client = client;
        this.renderer = renderer;
        this.shutdownTimeoutMs = cfg.getShutdownTimeout().toMillis();
        int threads = Math.max(1, cfg.getConsumerThreads());
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, SENDER_THREAD_PREFIX + counter.getAndIncrement());
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((th, ex) -> {
                    // 异步线程未捕获异常静默丢弃,绝不二次 log.error
                });
                return t;
            }
        };
        this.executor = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(cfg.getQueueCapacity()),
                tf,
                (r, ex) -> droppedCount.incrementAndGet()
        );
        this.executor.prestartAllCoreThreads();
    }

    @Override
    public void enqueue(AlertEvent event) {
        if (!running) return;
        try {
            executor.execute(() -> doSendEvent(event));
        } catch (Throwable t) {
            droppedCount.incrementAndGet();
        }
    }

    @Override
    public void enqueueSummary(SummaryEvent summary) {
        if (!running) return;
        try {
            executor.execute(() -> doSendSummary(summary));
        } catch (Throwable t) {
            droppedCount.incrementAndGet();
        }
    }

    @Override
    public long droppedCount() {
        return droppedCount.get();
    }

    private void doSendEvent(AlertEvent event) {
        try {
            String md = renderer.render(event);
            client.sendMarkdown(md);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("alert send interrupted");
        } catch (Throwable t) {
            log.debug("alert send failed: {}", t.toString());
        }
    }

    private void doSendSummary(SummaryEvent summary) {
        try {
            String md = renderer.render(summary);
            client.sendMarkdown(md);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("alert summary interrupted");
        } catch (Throwable t) {
            log.debug("alert summary failed: {}", t.toString());
        }
    }

    @Override
    public boolean isAutoStartup() { return true; }

    @Override
    public int getPhase() { return Integer.MAX_VALUE - 100; }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public void start() { running = true; }

    @Override
    public void stop() { stop(() -> {}); }

    @Override
    public void stop(Runnable cb) {
        running = false;
        executor.shutdown();
        try {
            executor.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        cb.run();
    }
}
