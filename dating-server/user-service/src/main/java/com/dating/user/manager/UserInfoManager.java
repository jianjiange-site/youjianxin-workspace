package com.dating.user.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dating.common.bizid.BizIdGenerator;
import com.dating.user.constant.CacheKeys;
import com.dating.user.entity.UserInfo;
import com.dating.user.mapper.UserInfoMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

// user_info 单表 CRUD + cache aside (key=user:profile:{userId} Hash with field "v")。
// 写后只删缓存,不双写;批量读不打缓存(U6 再加批量缓存策略)。
//
// 重要语义:本 manager 对外的所有 userId 入参 / 返回都是「业务主键 user_info.user_id」
// (BizIdGenerator 生成的 env+YYMMDD+seq,如 12605290001),不是物理 BIGSERIAL id。
// 这是为了让 OpenIM userID / JWT uid / 跨服务 metadata 自带环境位,prod/dev 不撞。
// 物理 id 仅在表内做 PK / @TableLogic deleted 关联,不外漏。
@Slf4j
@Component
public class UserInfoManager {

    private static final String HF_VALUE = "v";

    private final UserInfoMapper userInfoMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper redisObjectMapper;
    private final BizIdGenerator bizIdGenerator;

    public UserInfoManager(
            UserInfoMapper userInfoMapper,
            RedisTemplate<String, Object> redisTemplate,
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
            BizIdGenerator bizIdGenerator) {
        this.userInfoMapper = userInfoMapper;
        this.redisTemplate = redisTemplate;
        this.redisObjectMapper = redisObjectMapper;
        this.bizIdGenerator = bizIdGenerator;
    }

    // ResolveOrCreate 三种入口共用:插入仅含登录维度的最小占位行,返回业务 user_id。
    // 业务字段(nickname/age/...)等 onboarding 阶段才补。
    // user_type 不在这里 set,走 DB DEFAULT 2(BH);登录链路只服务真人,DH 由 admin 接口灌入。
    public Long insertPlaceholder(Short appName, Short platform) {
        UserInfo u = new UserInfo();
        u.setUserId(bizIdGenerator.next("user_info"));
        u.setAppName(appName == null ? 0 : appName);
        u.setPlatform(platform);
        u.setGender((short) 0);
        u.setRegulationStatus((short) 0);
        u.setPending(true);
        u.setLastOpenAt(OffsetDateTime.now());
        userInfoMapper.insert(u);
        return u.getUserId();
    }

    // 命中已存在用户时刷新 last_open_at;按业务 user_id 定位,不动其他字段、不清主资料缓存以减少抖动。
    public int touchLastOpenAt(Long userId) {
        UpdateWrapper<UserInfo> uw = new UpdateWrapper<>();
        uw.eq("user_id", userId)
                .set("last_open_at", OffsetDateTime.now())
                .set("updated_at", OffsetDateTime.now());
        return userInfoMapper.update(null, uw);
    }

    // cache aside 读:命中 → 反序列化为 UserInfo;miss → DB → 回写 Hash + TTL。
    // userId 是业务 user_id。
    public UserInfo getByUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        String key = CacheKeys.userProfile(userId);
        Object cached = redisTemplate.opsForHash().get(key, HF_VALUE);
        if (cached != null) {
            try {
                return redisObjectMapper.convertValue(cached, UserInfo.class);
            } catch (IllegalArgumentException e) {
                log.warn("user-service profile cache corrupted, evict key={}", key, e);
                redisTemplate.delete(key);
            }
        }
        UserInfo db = userInfoMapper.selectOne(
                new LambdaQueryWrapper<UserInfo>().eq(UserInfo::getUserId, userId));
        if (db != null) {
            redisTemplate.opsForHash().put(key, HF_VALUE, db);
            redisTemplate.expire(key, CacheKeys.USER_PROFILE_TTL);
        }
        return db;
    }

    // 批量场景禁 N+1:一条 in(...) 拿齐,按业务 user_id 查;缓存策略 U6 再补(逐 key MGET)。
    public List<UserInfo> batchGetByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        return userInfoMapper.selectList(
                new LambdaQueryWrapper<UserInfo>().in(UserInfo::getUserId, userIds));
    }

    // UpdateProfile 用:仅传入的非 null 字段进 SET;patch.userId 是业务主键,patch.id 不用 set。
    public int updateDynamic(UserInfo patch) {
        if (patch == null || patch.getUserId() == null) {
            throw new IllegalArgumentException("UserInfo.userId required for updateDynamic");
        }
        Long userId = patch.getUserId();
        // 避免 MyBatis-Plus 把 userId 也写进 SET 子句(它本来就是 WHERE 条件)
        patch.setUserId(null);
        int rows = userInfoMapper.update(patch,
                new LambdaUpdateWrapper<UserInfo>().eq(UserInfo::getUserId, userId));
        patch.setUserId(userId);
        evictProfile(userId);
        return rows;
    }

    // ConfirmAvatarUpload 用:仅更新 custom_avatar JSONB 字段。
    // 走 UserInfoMapper.updateCustomAvatarJson(原生 SQL + ::jsonb cast),避免 UpdateWrapper 把 String
    // 当 VARCHAR 写入 jsonb 列(PG 不允许 text→jsonb 隐式转换)。userId 是业务主键。
    public int updateCustomAvatar(Long userId, String customAvatarJson) {
        int rows = userInfoMapper.updateCustomAvatarJson(userId, customAvatarJson);
        evictProfile(userId);
        evictProfileBig(userId);
        return rows;
    }

    public void evictProfile(Long userId) {
        if (userId != null) {
            redisTemplate.delete(CacheKeys.userProfile(userId));
        }
    }

    public void evictProfileBig(Long userId) {
        if (userId != null) {
            redisTemplate.delete(CacheKeys.userProfileBig(userId));
        }
    }
}
