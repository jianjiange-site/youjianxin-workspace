package com.dating.common.alert;

import java.util.Map;

// 业务侧显式 API 门面。所有方法非阻塞,异常(限流/队列满)只丢弃不抛业务线程。
public interface AlertNotifier {

    // 关键告警:跳过签名窗口,只过全局令牌桶。不该被自动合并的场景用这个。
    void critical(String scene, Throwable ex, Map<String, String> context);

    // 与 Logback Appender 路径同优先级:走完整限流(签名窗口 + 全局令牌桶)。
    void error(String scene, Throwable ex, Map<String, String> context);

    // 无异常的纯文本警告。
    void warn(String scene, String message, Map<String, String> context);
}
