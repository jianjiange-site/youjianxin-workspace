package com.dating.common.alert;

// 仅用于消息路由(critical 跳过签名窗口)与 markdown 模板颜色;不与 SLF4J Level 对齐。
public enum AlertLevel {
    CRITICAL,
    ERROR,
    WARN
}
