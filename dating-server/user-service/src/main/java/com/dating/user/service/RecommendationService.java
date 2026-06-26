package com.dating.user.service;

import com.dating.user.entity.UserInfo;
import com.dating.user.service.dto.DhCityOverridePick;
import com.dating.user.service.dto.RecommendationQuery;

import java.util.List;
import java.util.Map;

/**
 * 召回服务(match-service D0/D1 队列生成的数据源)。
 *
 * <p>设计:
 * <ul>
 *   <li>无状态、无 cache:每次调用即查 DB;match-service 端按需多次调用做分层</li>
 *   <li>单表查询(user_info),不与 geo_city 等 JOIN(CLAUDE.md 红线 1)</li>
 *   <li>排序统一按 beauty_score DESC,调用方负责后续字典序 / 打分公式</li>
 * </ul>
 */
public interface RecommendationService {

    /**
     * DH 候选召回(D0/D1 共用)。
     *
     * <p>过滤:user_type = DH(1) + targetGender + age 区间 + beauty 区间 + race in + exclude
     * <br>排序:beauty_score DESC
     * <br>结果:top {@code query.limit} 条
     */
    List<UserInfo> listDhCandidates(RecommendationQuery query);

    /**
     * BH 候选召回(D0/D1 共用)。
     *
     * <p>Phase 1 实现:同城(city_id 相等);caller 的 city_id 由 service 层查 user_info 取得。
     * <br>过滤:user_type = BH(2) + targetGender + city_id = caller.cityId + age/beauty 区间
     *           + race + lastActive + exclude
     * <br>排序:beauty_score DESC
     * <br>caller 自身永远排除(避免推自己给自己)
     */
    List<UserInfo> nearbyUsers(long callerUserId, RecommendationQuery query);

    /**
     * 为 DH 卡片生成"同 state 不同 city"的位置覆盖值。
     *
     * <p>业务诉求:DH user_info 不存真实位置;但 feed 出卡时,要让 DH 跟 caller 在同一州、不同城市,
     * 既本地化又防穿帮。
     *
     * <p>逻辑:
     * <ol>
     *   <li>读 caller.state_code / city_id;caller 未填位置 → 返回空 map(前端按"无位置"渲染)</li>
     *   <li>geo_city 字典里取该 state 下所有城市(state 维度 in-memory 缓存,28k 行总量,~50 state)</li>
     *   <li>剔掉 caller.city_id 自己;剔光 → 返回空 map(单城市 state 的边缘情况)</li>
     *   <li>对每个 dh_user_id 用 hash(dh, caller) 确定性挑一城,同 (dh, caller) 跨次稳定</li>
     * </ol>
     *
     * @param callerUserId 当前刷 feed 的 caller
     * @param dhUserIds    需要生成覆盖值的 DH user_id 列表
     * @return key = dh_user_id;caller 没位置 / state 单城市时整个 map 为空
     */
    Map<Long, DhCityOverridePick> pickDhCitiesForCaller(long callerUserId, List<Long> dhUserIds);
}
