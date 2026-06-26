package com.dating.common.alert.send;

import com.dating.common.alert.AlertEvent;
import com.dating.common.alert.SummaryEvent;

// 异步发送入口。enqueue 不抛任何异常给业务线程,内部丢弃只计数。
public interface AlertSender {

    void enqueue(AlertEvent event);

    void enqueueSummary(SummaryEvent summary);

    long droppedCount();
}
