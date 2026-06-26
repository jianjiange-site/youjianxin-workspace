package com.dating.common.alert;

import java.net.InetAddress;
import java.net.UnknownHostException;

// 启动期一次性解析 hostname,后续读快照,避免每次告警都做 DNS 反解。
public final class HostInfo {

    private final String env;
    private final String service;
    private final String host;

    public HostInfo(String env, String service, String host) {
        this.env = (env == null || env.isBlank()) ? "unknown" : env;
        this.service = (service == null || service.isBlank()) ? "unknown" : service;
        this.host = (host == null || host.isBlank()) ? "unknown" : host;
    }

    public static String detectHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "unknown";
        }
    }

    public String env() { return env; }
    public String service() { return service; }
    public String host() { return host; }
}
