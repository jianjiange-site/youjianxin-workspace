package com.dating.match.recommend.dto;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * 用户偏好画像(从最近 30 天 RIGHT/SUPER_HI swipe 聚合得到)。
 *
 * <p>详见 dating-server/docs/match-service-prd-tech.md §4.2.1。
 *
 * <p>样本数 {@code sampleSize} 不足 {@link PreferenceBuilder#MIN_SAMPLE_FOR_PERSONALIZE} 时,
 * 调用方应该回退用户自身画像作 prior(避免噪声主导)。
 */
@Value
@Builder
public class Preference {

    /** 30 天内右划过的 target 年龄均值 */
    double ageMean;
    /** 标准差;0 时表示样本不足或全一样,Ranker 用兜底值(如 5) */
    double ageStd;

    /** 颜值分均值 */
    double beautyMean;
    double beautyStd;

    /** 人种分布:race → 占比(归一化和 = 1.0);未知 race 不计 */
    Map<String, Double> raceDist;

    /** RIGHT swipe 中 DH 比例(0~1);用于 D1 merge 阶段的个性化偏移(L2) */
    double dhBhRatio;

    /** 实际聚合的样本数 */
    int sampleSize;
}
