package com.dating.common.alert.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.dating.common.alert.AlertNotifier;
import com.dating.common.alert.AlertProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class WeWorkAppenderTest {

    @AfterEach
    void cleanup() {
        WeWorkAppender.reset();
    }

    @Test
    void droppedWhenNotInjected() {
        WeWorkAppender appender = new WeWorkAppender();
        AlertNotifier notifier = mock(AlertNotifier.class);
        // 不 inject:NOTIFIER==null,append 直接返回
        appender.doAppend(errorEvent("com.x.MyLogger", new RuntimeException()));
        verify(notifier, never()).error(any(), any(), any());
    }

    @Test
    void normalPathInvokesNotifierError() {
        AlertNotifier notifier = mock(AlertNotifier.class);
        WeWorkAppender.inject(notifier, defaultCfg());
        WeWorkAppender appender = startedAppender();
        appender.doAppend(errorEvent("com.x.MyLogger", new IllegalStateException("boom")));
        verify(notifier, times(1)).error(eq("com.x.MyLogger"), any(Throwable.class), any());
    }

    @Test
    void belowThresholdLevelIsSkipped() {
        AlertNotifier notifier = mock(AlertNotifier.class);
        WeWorkAppender.inject(notifier, defaultCfg());
        WeWorkAppender appender = startedAppender();
        appender.doAppend(eventAt("com.x.L", Level.WARN, null));
        verify(notifier, never()).error(any(), any(), any());
    }

    @Test
    void ignoredLoggerSkipped() {
        AlertNotifier notifier = mock(AlertNotifier.class);
        AlertProperties.Appender cfg = defaultCfg();
        cfg.setIgnoreLoggers(List.of("org.apache.kafka"));
        WeWorkAppender.inject(notifier, cfg);
        WeWorkAppender appender = startedAppender();
        appender.doAppend(errorEvent("org.apache.kafka.client.Sub", new RuntimeException()));
        verify(notifier, never()).error(any(), any(), any());
    }

    @Test
    void thresholdAcceptsWarnWhenConfigured() {
        AlertNotifier notifier = mock(AlertNotifier.class);
        AlertProperties.Appender cfg = defaultCfg();
        cfg.setLevel("WARN");
        WeWorkAppender.inject(notifier, cfg);
        WeWorkAppender appender = startedAppender();
        appender.doAppend(eventAt("com.x.L", Level.WARN, new RuntimeException("w")));
        verify(notifier, times(1)).error(any(), any(), any());
    }

    private static WeWorkAppender startedAppender() {
        WeWorkAppender a = new WeWorkAppender();
        a.setContext(new LoggerContext());
        a.start();
        return a;
    }

    private static AlertProperties.Appender defaultCfg() {
        return new AlertProperties.Appender();
    }

    private static LoggingEvent errorEvent(String logger, Throwable t) {
        return eventAt(logger, Level.ERROR, t);
    }

    private static LoggingEvent eventAt(String logger, Level level, Throwable t) {
        LoggingEvent ev = new LoggingEvent();
        ev.setLoggerName(logger);
        ev.setLevel(level);
        ev.setMessage("test");
        ev.setMDCPropertyMap(Map.of("traceId", "trace-x"));
        if (t != null) ev.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(t));
        return ev;
    }
}
