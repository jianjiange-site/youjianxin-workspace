package com.dating.user.service.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 召回查询参数(DH/BH 共用)。
 * 由 grpc 层从 proto 拆出,service 层用 MyBatis-Plus LambdaQueryWrapper 拼条件。
 *
 * <p>设计:
 * <ul>
 *   <li>match-service 端按需多次调用做分层(L0~L3),service 层不感知层级</li>
 *   <li>races 空表示不限人种(或 null);非空走 race IN (...)  OR race IS NULL(可选)</li>
 *   <li>excludeUserIds 上限 5000;超过由 grpc 层拒绝</li>
 * </ul>
 */
@Value
@Builder
public class RecommendationQuery {
    /**
     * 仅 DH 召回需要;BH 召回用 userType=2 写死,这里 0 表示由 service 层根据 RPC 入口决定。
     */
    Short userType;

    /**
     * 目标性别(异性恋假设),caller gender 的对立面;0 表示不限。
     */
    Short targetGender;

    /**
     * 年龄闭区间(0 = 不限)。
     */
    int ageMin;
    int ageMax;

    /**
     * 颜值分闭区间(0-100,0 = 不限上下界对应 0/100)。
     */
    int beautyMin;
    int beautyMax;

    /**
     * 优先人种列表;null 或空表示不限。
     */
    List<String> races;

    /**
     * BH 召回的同城基准 city_id;0 表示不按 city 过滤(DH 召回时传 0)。
     */
    long cityId;

    /**
     * 最近 N 天活跃过的用户;0 表示不限(DH 召回时传 0,BH D1 传 7)。
     */
    int lastActiveWithinDays;

    /**
     * 排除的 user_id 列表(已 swipe / 已 seen / 互拉黑)。
     */
    List<Long> excludeUserIds;

    /**
     * 召回数量上限。
     */
    int limit;
}
