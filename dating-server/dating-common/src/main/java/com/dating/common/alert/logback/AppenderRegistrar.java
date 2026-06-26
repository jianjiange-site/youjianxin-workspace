package com.dating.common.alert.logback;

import ch.qos.logback.classic.LoggerContext;
import com.dating.common.alert.AlertNotifier;
import com.dating.common.alert.AlertProperties;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;

// 在 Spring 完全启动后(ApplicationStartedEvent),把 WeWorkAppender 编程式 attach 到 root logger。
// 业务服务零 XML 改动:既不需要 logback-spring.xml,也不需要在 application.yml 配 Appender。
public class AppenderRegistrar implements ApplicationListener<ApplicationStartedEvent>, DisposableBean {

    private final AlertNotifier notifier;
    private final AlertProperties props;
    private volatile WeWorkAppender appender;

    public AppenderRegistrar(AlertNotifier notifier, AlertProperties props) {
        this.notifier = notifier;
        this.props = props;
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent ev) {
        WeWorkAppender.inject(notifier, props.getAppender());
        ILoggerFactory lf = LoggerFactory.getILoggerFactory();
        if (!(lf instanceof LoggerContext lc)) return;
        WeWorkAppender app = new WeWorkAppender();
        app.setName("WEWORK_ALERT");
        app.setContext(lc);
        app.start();
        lc.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(app);
        this.appender = app;
    }

    @Override
    public void destroy() {
        WeWorkAppender a = this.appender;
        if (a == null) return;
        ILoggerFactory lf = LoggerFactory.getILoggerFactory();
        if (lf instanceof LoggerContext lc) {
            lc.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(a);
        }
        a.stop();
        WeWorkAppender.reset();
    }
}
