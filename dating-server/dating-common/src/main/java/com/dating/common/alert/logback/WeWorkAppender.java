package com.dating.common.alert.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.dating.common.alert.AlertNotifier;
import com.dating.common.alert.AlertProperties;
import com.dating.common.alert.send.AsyncAlertSender;

import java.util.List;
import java.util.Map;

// Logback Appender 子类。监听 root logger 的 ERROR 级别(可配),触发企业微信告警。
// 静态注入 NOTIFIER/CFG:Logback 比 Spring 早活跃,启动期 NOTIFIER==null 时直接丢弃。
// 防递归三重保险:静态 ThreadLocal、消费线程 name 前缀短路、catch 全部异常静默。
public class WeWorkAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static volatile AlertNotifier NOTIFIER;
    private static volatile AlertProperties.Appender CFG;

    private static final ThreadLocal<Boolean> SENDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // AppenderRegistrar 在 ApplicationStartedEvent 里调
    public static void inject(AlertNotifier notifier, AlertProperties.Appender cfg) {
        NOTIFIER = notifier;
        CFG = cfg;
    }

    // 测试用,重置静态状态
    public static void reset() {
        NOTIFIER = null;
        CFG = null;
        SENDING.set(Boolean.FALSE);
    }

    @Override
    protected void append(ILoggingEvent event) {
        AlertNotifier notifier = NOTIFIER;
        AlertProperties.Appender cfg = CFG;
        if (notifier == null || cfg == null) return;
        if (SENDING.get()) return;
        // 异步消费线程产生的 log 一律不告(防止 HTTP 失败时的二次 log 触发循环)
        if (Thread.currentThread().getName().startsWith(AsyncAlertSender.SENDER_THREAD_PREFIX)) return;
        if (!event.getLevel().isGreaterOrEqual(threshold(cfg))) return;
        String loggerName = event.getLoggerName();
        if (isIgnored(loggerName, cfg.getIgnoreLoggers())) return;

        Throwable t = extractThrowable(event);
        SENDING.set(Boolean.TRUE);
        try {
            // Notifier 内部 MDC.getCopyOfContextMap 会拿到当前线程 MDC(traceId/userId),
            // 业务 context map 留空(Appender 路径无法感知业务自定义字段)。
            notifier.error(loggerName, t, Map.of());
        } catch (Throwable ignored) {
            // 静默,绝不二次 log.error
        } finally {
            SENDING.set(Boolean.FALSE);
        }
    }

    private static Level threshold(AlertProperties.Appender cfg) {
        return Level.toLevel(cfg.getLevel(), Level.ERROR);
    }

    private static boolean isIgnored(String name, List<String> ignoreList) {
        if (name == null || ignoreList == null || ignoreList.isEmpty()) return false;
        for (String prefix : ignoreList) {
            if (prefix != null && !prefix.isEmpty() && name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static Throwable extractThrowable(ILoggingEvent event) {
        IThrowableProxy tp = event.getThrowableProxy();
        if (tp instanceof ThrowableProxy tpx) return tpx.getThrowable();
        return null;
    }
}
