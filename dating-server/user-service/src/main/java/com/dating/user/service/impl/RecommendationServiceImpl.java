package com.dating.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.user.entity.GeoCity;
import com.dating.user.entity.UserInfo;
import com.dating.user.mapper.GeoCityMapper;
import com.dating.user.mapper.UserInfoMapper;
import com.dating.user.service.RecommendationService;
import com.dating.user.service.dto.DhCityOverridePick;
import com.dating.user.service.dto.RecommendationQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 召回服务实现。
 *
 * <p>查询直接走 MyBatis-Plus {@link LambdaQueryWrapper};单表 user_info,无 JOIN。
 * 索引由 V5 的 {@code idx_user_info_recall (user_type, gender, city_id, age, beauty_score)}
 * partial index 支撑(WHERE deleted = false)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {

    /** DB 取值:1=DH, 2=BH */
    private static final short USER_TYPE_DH = 1;
    private static final short USER_TYPE_BH = 2;

    /** pickDhCitiesForCaller 单次入参 DH 数上限 */
    private static final int DH_PICK_BATCH_LIMIT = 500;

    private final UserInfoMapper userInfoMapper;
    private final GeoCityMapper geoCityMapper;

    /**
     * state_code → 该 state 下的城市列表。geo_city 是字典表,运行期不变;
     * 进程级 lazy load 即可,无 TTL。容器重启即 reset(重启就重 warm 一次,影响微乎其微)。
     */
    private final ConcurrentHashMap<String, List<CityRow>> cityByState = new ConcurrentHashMap<>();

    @Override
    public List<UserInfo> listDhCandidates(RecommendationQuery q) {
        if (q == null || q.getLimit() <= 0) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<UserInfo> w = baseQuery(USER_TYPE_DH, q);
        // DH 召回不按 city_id / lastActive 过滤(运营保证 DH 池全局可见)
        w.orderByDesc(UserInfo::getBeautyScore);
        w.last("LIMIT " + q.getLimit());
        return userInfoMapper.selectList(w);
    }

    @Override
    public List<UserInfo> nearbyUsers(long callerUserId, RecommendationQuery q) {
        if (q == null || q.getLimit() <= 0) {
            return Collections.emptyList();
        }
        // Phase 1 实现:同城。caller 的 city_id 在 grpc 层已经传入 query.cityId;若为 0 直接空集合。
        if (q.getCityId() <= 0) {
            log.debug("nearbyUsers: callerUserId={} 无 city_id,Phase 1 同城策略下返回空", callerUserId);
            return Collections.emptyList();
        }
        LambdaQueryWrapper<UserInfo> w = baseQuery(USER_TYPE_BH, q);
        w.eq(UserInfo::getCityId, q.getCityId());
        w.ne(UserInfo::getUserId, callerUserId);          // 排除 caller 自己
        if (q.getLastActiveWithinDays() > 0) {
            OffsetDateTime since = OffsetDateTime.now().minusDays(q.getLastActiveWithinDays());
            w.ge(UserInfo::getLastOpenAt, since);
        }
        w.orderByDesc(UserInfo::getBeautyScore);
        w.last("LIMIT " + q.getLimit());
        return userInfoMapper.selectList(w);
    }

    @Override
    public Map<Long, DhCityOverridePick> pickDhCitiesForCaller(long callerUserId, List<Long> dhUserIds) {
        if (dhUserIds == null || dhUserIds.isEmpty()) {
            return Collections.emptyMap();
        }
        if (dhUserIds.size() > DH_PICK_BATCH_LIMIT) {
            // grpc 层已校验,这里再兜底一道
            throw new IllegalArgumentException("dh_user_ids size exceeds " + DH_PICK_BATCH_LIMIT);
        }
        // 1) 读 caller 的 state / city
        LambdaQueryWrapper<UserInfo> w = new LambdaQueryWrapper<>();
        w.eq(UserInfo::getUserId, callerUserId);
        w.select(UserInfo::getStateCode, UserInfo::getCityId);
        w.last("LIMIT 1");
        UserInfo caller = userInfoMapper.selectOne(w);
        if (caller == null
                || caller.getStateCode() == null
                || caller.getStateCode().isBlank()) {
            // caller 未填位置 → 整张 map 空
            return Collections.emptyMap();
        }
        String stateCode = caller.getStateCode();
        long callerCityId = caller.getCityId() == null ? 0L : caller.getCityId();

        // 2) 取 state 下所有城市(进程内 lazy cache)
        List<CityRow> all = citiesInState(stateCode);
        if (all.isEmpty()) {
            log.warn("pickDhCitiesForCaller: state {} 字典命中 0 城市,callerUserId={}", stateCode, callerUserId);
            return Collections.emptyMap();
        }

        // 3) 排除 caller 自己的 city
        List<CityRow> pickPool;
        if (callerCityId > 0) {
            pickPool = all.stream().filter(c -> c.cityId() != callerCityId).toList();
        } else {
            pickPool = all;
        }
        if (pickPool.isEmpty()) {
            // 单城市 state 边缘情况(夏威夷某些州可能极端;基本不会触发)
            return Collections.emptyMap();
        }

        // 4) 对每个 dh 按 (dhId, callerId) seed 确定性选一城
        Map<Long, DhCityOverridePick> out = new HashMap<>(dhUserIds.size());
        for (Long dhId : dhUserIds) {
            if (dhId == null || dhId <= 0) continue;
            int idx = pickIndex(callerUserId, dhId, pickPool.size());
            CityRow pick = pickPool.get(idx);
            out.put(dhId, new DhCityOverridePick(stateCode, pick.name()));
        }
        return out;
    }

    /** 进程内 lazy:state → 城市列表。geo_city 是字典不变,重启即 reset 即可。 */
    private List<CityRow> citiesInState(String stateCode) {
        return cityByState.computeIfAbsent(stateCode, sc -> {
            LambdaQueryWrapper<GeoCity> w = new LambdaQueryWrapper<>();
            w.eq(GeoCity::getStateCode, sc);
            w.select(GeoCity::getId, GeoCity::getCity);
            return geoCityMapper.selectList(w).stream()
                    .map(g -> new CityRow(g.getId(), g.getCity()))
                    .toList();
        });
    }

    /**
     * SplitMix64 风格的雪崩 hash:对 (callerId, dhId) 稳定 seed,把高低位充分混合后取模。
     * 同一 (caller, dh) 跨次稳定;不同 caller 看同一 dh 命中不同 idx。
     */
    private static int pickIndex(long callerId, long dhId, int size) {
        long x = callerId * 0x9E3779B97F4A7C15L + dhId;   // 黄金分割乘子混合,避免简单 XOR 同对称
        x ^= x >>> 33;
        x *= 0xFF51AFD7ED558CCDL;
        x ^= x >>> 33;
        x *= 0xC4CEB9FE1A85EC53L;
        x ^= x >>> 33;
        return Math.floorMod(x, size);
    }

    /**
     * 通用过滤(适用 DH / BH 共有的维度):gender + age + beauty + race + exclude。
     * deleted = false 由 MyBatis-Plus @TableLogic 自动追加。
     */
    private LambdaQueryWrapper<UserInfo> baseQuery(short userType, RecommendationQuery q) {
        LambdaQueryWrapper<UserInfo> w = new LambdaQueryWrapper<>();
        w.eq(UserInfo::getUserType, userType);
        if (q.getTargetGender() != null && q.getTargetGender() > 0) {
            w.eq(UserInfo::getGender, q.getTargetGender());
        }
        if (q.getAgeMin() > 0) {
            w.ge(UserInfo::getAge, (short) q.getAgeMin());
        }
        if (q.getAgeMax() > 0) {
            w.le(UserInfo::getAge, (short) q.getAgeMax());
        }
        if (q.getBeautyMin() > 0) {
            w.ge(UserInfo::getBeautyScore, (short) q.getBeautyMin());
        }
        if (q.getBeautyMax() > 0) {
            w.le(UserInfo::getBeautyScore, (short) q.getBeautyMax());
        }
        if (q.getRaces() != null && !q.getRaces().isEmpty()) {
            w.in(UserInfo::getRace, q.getRaces());
        }
        if (q.getExcludeUserIds() != null && !q.getExcludeUserIds().isEmpty()) {
            w.notIn(UserInfo::getUserId, q.getExcludeUserIds());
        }
        return w;
    }

    private record CityRow(long cityId, String name) {}
}
