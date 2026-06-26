package com.dating.common.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// dating.alert.* 配置;corp-secret 等敏感值走 Nacos,绝不入仓。
// enabled=true 时 wework.corp-id / corp-secret / agent-id 必填,缺一启动期 fail-fast。
@ConfigurationProperties("dating.alert")
public class AlertProperties {

    private boolean enabled = true;
    private String env;
    private String service;

    private WeWork wework = new WeWork();
    private Throttle throttle = new Throttle();
    private Appender appender = new Appender();
    private Async async = new Async();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public WeWork getWework() { return wework; }
    public void setWework(WeWork wework) { this.wework = wework; }

    public Throttle getThrottle() { return throttle; }
    public void setThrottle(Throttle throttle) { this.throttle = throttle; }

    public Appender getAppender() { return appender; }
    public void setAppender(Appender appender) { this.appender = appender; }

    public Async getAsync() { return async; }
    public void setAsync(Async async) { this.async = async; }

    public static class WeWork {
        // 企业微信接口 base url;集成测试时通过 @DynamicPropertySource 改成本地桩端口
        private String baseUrl = "https://qyapi.weixin.qq.com";
        private String corpId = "";
        private String corpSecret = "";
        private long agentId = 0L;
        // 接收对象,| 分隔多个;全空则默认 touser=@all 全员
        private String toUser = "";
        private String toParty = "";
        private String toTag = "";
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 5000;
        private Duration tokenRefreshSkew = Duration.ofMinutes(5);

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getCorpId() { return corpId; }
        public void setCorpId(String corpId) { this.corpId = corpId; }
        public String getCorpSecret() { return corpSecret; }
        public void setCorpSecret(String corpSecret) { this.corpSecret = corpSecret; }
        public long getAgentId() { return agentId; }
        public void setAgentId(long agentId) { this.agentId = agentId; }
        public String getToUser() { return toUser; }
        public void setToUser(String toUser) { this.toUser = toUser; }
        public String getToParty() { return toParty; }
        public void setToParty(String toParty) { this.toParty = toParty; }
        public String getToTag() { return toTag; }
        public void setToTag(String toTag) { this.toTag = toTag; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
        public Duration getTokenRefreshSkew() { return tokenRefreshSkew; }
        public void setTokenRefreshSkew(Duration tokenRefreshSkew) { this.tokenRefreshSkew = tokenRefreshSkew; }
    }

    public static class Throttle {
        private Duration windowDuration = Duration.ofMinutes(10);
        private int maxPerWindow = 3;
        private int globalPerMinute = 60;
        private int globalBurst = 30;
        private Duration cleanupInterval = Duration.ofMinutes(1);

        public Duration getWindowDuration() { return windowDuration; }
        public void setWindowDuration(Duration windowDuration) { this.windowDuration = windowDuration; }
        public int getMaxPerWindow() { return maxPerWindow; }
        public void setMaxPerWindow(int maxPerWindow) { this.maxPerWindow = maxPerWindow; }
        public int getGlobalPerMinute() { return globalPerMinute; }
        public void setGlobalPerMinute(int globalPerMinute) { this.globalPerMinute = globalPerMinute; }
        public int getGlobalBurst() { return globalBurst; }
        public void setGlobalBurst(int globalBurst) { this.globalBurst = globalBurst; }
        public Duration getCleanupInterval() { return cleanupInterval; }
        public void setCleanupInterval(Duration cleanupInterval) { this.cleanupInterval = cleanupInterval; }
    }

    public static class Appender {
        private boolean enabled = true;
        private String level = "ERROR";
        private List<String> ignoreLoggers = defaultIgnore();

        private static List<String> defaultIgnore() {
            List<String> list = new ArrayList<>(4);
            list.add("org.apache.kafka");
            list.add("io.lettuce.core");
            list.add("com.alibaba.nacos");
            list.add("org.springframework.boot.actuate");
            return list;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public List<String> getIgnoreLoggers() { return ignoreLoggers; }
        public void setIgnoreLoggers(List<String> ignoreLoggers) { this.ignoreLoggers = ignoreLoggers; }
    }

    public static class Async {
        private int queueCapacity = 1024;
        private int consumerThreads = 1;
        private Duration shutdownTimeout = Duration.ofSeconds(5);

        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public int getConsumerThreads() { return consumerThreads; }
        public void setConsumerThreads(int consumerThreads) { this.consumerThreads = consumerThreads; }
        public Duration getShutdownTimeout() { return shutdownTimeout; }
        public void setShutdownTimeout(Duration shutdownTimeout) { this.shutdownTimeout = shutdownTimeout; }
    }
}
