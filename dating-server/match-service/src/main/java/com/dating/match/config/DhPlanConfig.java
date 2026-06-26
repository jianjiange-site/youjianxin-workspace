package com.dating.match.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * DH 模拟计划阈值(对齐 docs §6.4)。
 *
 * <p>Nacos {@code match-service-<profile>.yaml} 提供:
 * <pre>
 * match:
 *   dh-plan:
 *     online-count-range:    { min: 5, max: 10 }
 *     offline-count-range:   { min: 3, max: 6 }
 *     online-cooldown-seconds: 7200
 *     offline-threshold-seconds: 1200
 *     offline-lookback-seconds: 10800
 *     online-execute-window-min: 30
 *     offline-execute-window-min: 30
 *     online-lookback-seconds:  1800     # 游标最大回看;超过 → 告警 + 重置 cursor
 *     visit-ratio: 0.6
 *     daily-dh-like-cap:  15
 *     daily-dh-visit-cap: 25
 *     candidate-pool-size: 50            # 单次 listDhCandidates 取多大候选池(够 5~10 张里采样)
 *     bh-batch-limit: 5000               # 单轮单 scheduler 处理的 BH 上限
 *     like-content-templates:
 *       - { content: "你的照片好看!",   gender-pref: ANY }
 *       - { content: "想跟你聊聊~",       gender-pref: ANY }
 *       - { content: "你很有魅力哦",     gender-pref: MALE }
 *       - { content: "感觉你很有故事",   gender-pref: FEMALE }
 * </pre>
 *
 * <p>所有字段都有 fallback 默认值,Nacos 缺失也能起服。
 */
@Configuration
@ConfigurationProperties(prefix = "match.dh-plan")
@Data
public class DhPlanConfig {

    private IntRange onlineCountRange = new IntRange(5, 10);
    private IntRange offlineCountRange = new IntRange(3, 6);

    /** ONLINE 计划单用户冷却(秒),docs §6.4 默认 2h */
    private int onlineCooldownSeconds = 7200;

    /** OFFLINE 阈值:用户离线至少这么久才算"离线用户"(秒),默认 20min */
    private int offlineThresholdSeconds = 1200;

    /** OFFLINE 游标最大回看(秒),默认 3h */
    private int offlineLookbackSeconds = 10800;

    /** ONLINE 游标最大回看(秒),默认 30min;超过 → 告警 + 重置 cursor */
    private int onlineLookbackSeconds = 1800;

    /** ONLINE execute_time 在 [now, now + N min] 内均匀随机分布 */
    private int onlineExecuteWindowMin = 30;

    /** OFFLINE execute_time 同样分布窗口 */
    private int offlineExecuteWindowMin = 30;

    /** VISIT / (LIKE + VISIT) 比例,默认 0.6 → 60% VISIT, 40% LIKE */
    private double visitRatio = 0.6;

    /** 单 BH 24h 内最多被 DH like 多少条 */
    private int dailyDhLikeCap = 15;

    /** 单 BH 24h 内最多被 DH visit 多少条 */
    private int dailyDhVisitCap = 25;

    /** generator 单次 listDhCandidates 召回候选池大小;够 5~10 张里采样 + 留余量 */
    private int candidatePoolSize = 50;

    /** 单轮单 scheduler 处理的 BH 上限(对齐 im-service ListXxx limit 默认值) */
    private int bhBatchLimit = 5000;

    /** LIKE 文案模板池;按目标 BH 的 gender 筛后随机抽 */
    private List<TemplateEntry> likeContentTemplates = defaultTemplates();

    public IntRange countRangeForScene(boolean online) {
        return online ? onlineCountRange : offlineCountRange;
    }

    public int executeWindowMinForScene(boolean online) {
        return online ? onlineExecuteWindowMin : offlineExecuteWindowMin;
    }

    private static List<TemplateEntry> defaultTemplates() {
        List<TemplateEntry> list = new ArrayList<>();
        list.add(new TemplateEntry("你的照片好看!", "ANY"));
        list.add(new TemplateEntry("想跟你聊聊~", "ANY"));
        list.add(new TemplateEntry("hi, nice to meet you", "ANY"));
        list.add(new TemplateEntry("你很有魅力哦", "MALE"));
        list.add(new TemplateEntry("感觉你很有故事", "FEMALE"));
        return list;
    }

    @Data
    public static class IntRange {
        /** 闭区间下界 */
        private int min;
        /** 闭区间上界 */
        private int max;

        public IntRange() {}

        public IntRange(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }

    @Data
    public static class TemplateEntry {
        private String content;
        /** "MALE" / "FEMALE" / "ANY";按目标 BH gender 匹配,ANY 通配 */
        private String genderPref;

        public TemplateEntry() {}

        public TemplateEntry(String content, String genderPref) {
            this.content = content;
            this.genderPref = genderPref;
        }
    }
}
