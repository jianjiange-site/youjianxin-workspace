package com.dating.common.alert;

import com.dating.common.alert.logback.AppenderRegistrar;
import com.dating.common.alert.send.AlertSender;
import com.dating.common.alert.send.AsyncAlertSender;
import com.dating.common.alert.send.MessageRenderer;
import com.dating.common.alert.send.WeWorkAccessTokenManager;
import com.dating.common.alert.send.WeWorkMessageClient;
import com.dating.common.alert.throttle.AlertThrottler;
import com.dating.common.alert.throttle.ThrottleRegistry;
import com.dating.common.alert.throttle.TokenBucket;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

// dating.alert.* → 告警链路所有 bean。dating.alert.enabled=true(默认)时若 wework.corp-id/corp-secret/agent-id 缺失,启动期 fail-fast。
@AutoConfiguration
@EnableConfigurationProperties(AlertProperties.class)
@ConditionalOnClass(ch.qos.logback.classic.Logger.class)
@ConditionalOnProperty(prefix = "dating.alert", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AlertAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HostInfo alertHostInfo(AlertProperties props, Environment env) {
        String envName = firstNonBlank(props.getEnv(), env.getProperty("spring.profiles.active"));
        if (envName == null || envName.isBlank()) envName = "local";
        String service = firstNonBlank(props.getService(), env.getProperty("spring.application.name"));
        if (service == null || service.isBlank()) service = "unknown";
        return new HostInfo(envName, service, HostInfo.detectHost());
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpClient alertHttpClient(AlertProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getWework().getConnectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public WeWorkAccessTokenManager weWorkAccessTokenManager(AlertProperties props, HttpClient alertHttpClient) {
        validate(props);
        return new WeWorkAccessTokenManager(props.getWework(), alertHttpClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public WeWorkMessageClient weWorkMessageClient(AlertProperties props,
                                                   WeWorkAccessTokenManager tokenManager,
                                                   HttpClient alertHttpClient) {
        return new WeWorkMessageClient(props.getWework(), tokenManager, alertHttpClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageRenderer alertMessageRenderer() {
        return new MessageRenderer();
    }

    // SmartLifecycle 自己处理生命周期;不让 Spring 走默认 destroyMethod 重复 stop
    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public AsyncAlertSender alertSender(AlertProperties props,
                                        WeWorkMessageClient weWorkMessageClient,
                                        MessageRenderer alertMessageRenderer) {
        return new AsyncAlertSender(props.getAsync(), weWorkMessageClient, alertMessageRenderer);
    }

    @Bean(name = "alertScheduler", destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean(name = "alertScheduler")
    public ScheduledExecutorService alertScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dating-alert-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenBucket alertGlobalBucket(AlertProperties props) {
        AlertProperties.Throttle t = props.getThrottle();
        return new TokenBucket(t.getGlobalBurst(), t.getGlobalPerMinute());
    }

    @Bean
    @ConditionalOnMissingBean
    public ThrottleRegistry alertThrottleRegistry(AlertProperties props,
                                                  ScheduledExecutorService alertScheduler,
                                                  AlertSender alertSender) {
        return new ThrottleRegistry(props.getThrottle(), alertScheduler, alertSender);
    }

    @Bean
    @ConditionalOnMissingBean
    public AlertThrottler alertThrottler(ThrottleRegistry alertThrottleRegistry, TokenBucket alertGlobalBucket) {
        return new AlertThrottler(alertThrottleRegistry, alertGlobalBucket);
    }

    @Bean
    @ConditionalOnMissingBean
    public AlertNotifier alertNotifier(AlertThrottler alertThrottler,
                                       AlertSender alertSender,
                                       HostInfo alertHostInfo,
                                       AlertProperties props) {
        return new DefaultAlertNotifier(alertThrottler, alertSender, alertHostInfo, props.isEnabled());
    }

    @Bean
    @ConditionalOnProperty(prefix = "dating.alert.appender", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public AppenderRegistrar alertAppenderRegistrar(AlertNotifier alertNotifier, AlertProperties props) {
        return new AppenderRegistrar(alertNotifier, props);
    }

    private static void validate(AlertProperties props) {
        AlertProperties.WeWork w = props.getWework();
        requireNonBlank(w.getCorpId(), "corp-id");
        requireNonBlank(w.getCorpSecret(), "corp-secret");
        if (w.getAgentId() <= 0) {
            throw new IllegalStateException("dating.alert.wework.agent-id must be > 0 when dating.alert.enabled=true");
        }
        if (w.getBaseUrl() == null || w.getBaseUrl().isBlank()) {
            throw new IllegalStateException("dating.alert.wework.base-url must not be blank");
        }
    }

    private static void requireNonBlank(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "dating.alert.wework." + key + " must be set when dating.alert.enabled=true");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
