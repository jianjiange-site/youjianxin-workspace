package com.dating.common.alert.send;

import com.dating.common.alert.AlertEvent;
import com.dating.common.alert.SummaryEvent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Map;

// AlertEvent / SummaryEvent → 企业微信 markdown 文本。UTF-8 安全截断,不在多字节中间断字。
public class MessageRenderer {

    private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ISO_INSTANT;

    public String render(AlertEvent e) {
        return switch (e.level()) {
            case CRITICAL -> renderCritical(e);
            case ERROR, WARN -> renderError(e);
        };
    }

    public String render(SummaryEvent s) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("### <font color=\"info\">[SUMMARY] ")
                .append(s.service()).append(" @ ").append(s.env()).append("</font>\n\n");
        long total = (long) s.accepted() + (long) s.dropped();
        sb.append("过去 ").append(formatDuration(s.windowDuration()))
                .append(" 内异常签名 `").append(Long.toHexString(s.signature())).append("`")
                .append(" 共出现 **").append(total).append("** 次,")
                .append("已发送 ").append(s.accepted()).append(" 条,")
                .append("截流 **").append(s.dropped()).append("** 条。\n\n");
        sb.append("**样例**: `").append(s.exceptionClass()).append("` @ `").append(s.topFrame()).append("`\n");
        sb.append("**首次**: ").append(ISO_UTC.format(s.firstSeen())).append("\n");
        sb.append("**最近**: ").append(ISO_UTC.format(s.lastSeen())).append("\n");
        return sb.toString();
    }

    private String renderCritical(AlertEvent e) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("# <font color=\"warning\">[CRITICAL] ")
                .append(e.service()).append(" @ ").append(e.env()).append("</font>\n\n");
        sb.append("**场景**: ").append(e.scene()).append("\n");
        appendExceptionLines(sb, e);
        sb.append("**traceId**: `").append(mdc(e, "traceId")).append("`\n");
        sb.append("**userId**: `").append(mdc(e, "userId")).append("`\n");
        sb.append("**host**: ").append(e.host()).append("\n");
        sb.append("**时间**: ").append(ISO_UTC.format(e.timestamp())).append("\n");
        if (!e.context().isEmpty()) {
            sb.append("\n**业务上下文**:\n");
            StringBuilder ctx = new StringBuilder(128);
            for (Map.Entry<String, String> ent : e.context().entrySet()) {
                ctx.append("> ").append(ent.getKey()).append(": ").append(ent.getValue()).append("\n");
            }
            sb.append(truncateUtf8(ctx.toString(), 800));
        }
        if (e.throwable() != null) {
            sb.append("\n**堆栈**:\n```\n").append(formatStack(e.throwable(), 8, 2000)).append("\n```");
        }
        return sb.toString();
    }

    private String renderError(AlertEvent e) {
        StringBuilder sb = new StringBuilder(384);
        sb.append("## <font color=\"warning\">[").append(e.level().name()).append("] ")
                .append(e.service()).append(" @ ").append(e.env()).append("</font>\n\n");
        sb.append("**logger / scene**: ").append(e.scene()).append("\n");
        if (e.throwable() != null) {
            String msg = e.throwable().getMessage();
            sb.append("**异常**: `").append(e.throwable().getClass().getName()).append("`");
            if (msg != null && !msg.isBlank()) sb.append(": ").append(truncateUtf8(msg, 150));
            sb.append("\n");
        } else if (!e.message().isBlank()) {
            sb.append("**消息**: ").append(truncateUtf8(e.message(), 200)).append("\n");
        }
        sb.append("**traceId**: `").append(mdc(e, "traceId")).append("`  ");
        sb.append("**userId**: `").append(mdc(e, "userId")).append("`\n");
        sb.append("**host**: ").append(e.host()).append("  **时间**: ")
                .append(ISO_UTC.format(e.timestamp())).append("\n");
        if (e.throwable() != null) {
            sb.append("\n```\n").append(formatStack(e.throwable(), 5, 1500)).append("\n```");
        }
        return sb.toString();
    }

    private void appendExceptionLines(StringBuilder sb, AlertEvent e) {
        if (e.throwable() != null) {
            sb.append("**异常**: `").append(e.throwable().getClass().getName()).append("`\n");
            String msg = e.throwable().getMessage();
            if (msg != null && !msg.isBlank()) {
                sb.append("**消息**: ").append(truncateUtf8(msg, 200)).append("\n");
            }
        } else if (!e.message().isBlank()) {
            sb.append("**消息**: ").append(truncateUtf8(e.message(), 200)).append("\n");
        }
    }

    private static String mdc(AlertEvent e, String key) {
        String v = e.mdcSnapshot().get(key);
        return (v == null || v.isBlank()) ? "-" : v;
    }

    static String truncateUtf8(String s, int maxBytes) {
        if (s == null) return "";
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return s;
        int end = maxBytes;
        while (end > 0 && (b[end] & 0xC0) == 0x80) end--;
        return new String(b, 0, end, StandardCharsets.UTF_8) + "…";
    }

    static String formatStack(Throwable t, int maxLines, int maxBytes) {
        if (t == null) return "(no stack)";
        StackTraceElement[] st = t.getStackTrace();
        if (st == null || st.length == 0) return "(no stack)";
        int n = Math.min(maxLines, st.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String line = "at " + st[i].toString();
            if (line.length() > 200) line = line.substring(0, 200) + "…";
            sb.append(line);
            if (i < n - 1) sb.append('\n');
        }
        return truncateUtf8(sb.toString(), maxBytes);
    }

    static String formatDuration(Duration d) {
        long sec = d.getSeconds();
        if (sec < 60) return sec + "s";
        if (sec < 3600) return (sec / 60) + "m";
        return (sec / 3600) + "h";
    }
}
