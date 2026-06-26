package com.dating.user.service.impl;

import com.dating.user.constant.GenderMapping;
import com.dating.user.constant.UserTypeMapping;
import com.dating.user.converter.UserInfoConverter;
import com.dating.user.converter.UserInterestConverter;
import com.dating.user.entity.UserInfo;
import com.dating.user.entity.UserInterest;
import com.dating.user.exception.BizException;
import com.dating.user.exception.ErrorCodes;
import com.dating.user.manager.UserInfoManager;
import com.dating.user.manager.UserInterestManager;
import com.dating.user.service.UserProfileService;
import com.dating.user.vo.UserInterestVO;
import com.dating.user.vo.UserProfileVO;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjiange.proto.user.Gender;
import com.jianjiange.proto.user.UpdateProfileRequest;
import com.jianjiange.proto.user.UpsertOnboardingRequest;
import com.jianjiange.proto.user.UserType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Profile 4 RPC service 实现:
//   GetProfile / BatchGetProfile: 解析 custom_avatar JSONB 仅提取 object_key,
//     不签 URL —— 头像走 CDN public,App 侧自拼 ${cdnBaseUrl}/${bucket}/${key}。
//   UpdateProfile: 动态 SET (proto optional hasXxx → patch entity 字段);写后删 user:profile:* 缓存。
//   UpsertOnboarding: 写 gender/birthday/... + pending=false,事务内一次写完;返回最新 profile。
@Slf4j
@Service
public class UserProfileServiceImpl implements UserProfileService {

    private static final int MAX_BATCH = 200;

    private final UserInfoManager userInfoManager;
    private final UserInterestManager interestManager;
    private final UserInfoConverter userInfoConverter;
    private final UserInterestConverter interestConverter;
    private final ObjectMapper redisObjectMapper;
    private final TransactionTemplate txTemplate;

    public UserProfileServiceImpl(
            UserInfoManager userInfoManager,
            UserInterestManager interestManager,
            UserInfoConverter userInfoConverter,
            UserInterestConverter interestConverter,
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
            PlatformTransactionManager transactionManager) {
        this.userInfoManager = userInfoManager;
        this.interestManager = interestManager;
        this.userInfoConverter = userInfoConverter;
        this.interestConverter = interestConverter;
        this.redisObjectMapper = redisObjectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public UserProfileVO getProfile(long targetUserId) {
        if (targetUserId <= 0) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "targetUserId must be positive");
        }
        UserInfo u = userInfoManager.getByUserId(targetUserId);
        if (u == null) {
            throw new BizException(ErrorCodes.USER_NOT_FOUND, "user not found: " + targetUserId);
        }
        UserProfileVO vo = userInfoConverter.toVO(u);
        List<UserInterestVO> interests = interestConverter.toVOList(interestManager.findByUserId(targetUserId));
        vo.setInterests(interests);
        fillAvatarKeys(vo, u.getCustomAvatar());
        return vo;
    }

    @Override
    public List<UserProfileVO> batchGetProfile(Collection<Long> targetUserIds) {
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            return Collections.emptyList();
        }
        if (targetUserIds.size() > MAX_BATCH) {
            throw new BizException(ErrorCodes.BATCH_SIZE_EXCEEDED,
                    "batch size > " + MAX_BATCH + ": " + targetUserIds.size());
        }
        // 去重保序
        Set<Long> uniqueOrdered = new LinkedHashSet<>(targetUserIds);

        List<UserInfo> users = userInfoManager.batchGetByUserIds(uniqueOrdered);
        if (users.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, UserInfo> userByUserId = new HashMap<>(users.size() * 2);
        for (UserInfo u : users) {
            userByUserId.put(u.getUserId(), u);
        }

        // 兴趣一次 IN 拿齐,按业务 user_id 分桶
        List<UserInterest> allInterests = interestManager.findByUserIds(userByUserId.keySet());
        Map<Long, List<UserInterest>> interestsByUser = new HashMap<>(userByUserId.size() * 2);
        for (UserInterest i : allInterests) {
            interestsByUser.computeIfAbsent(i.getUserId(), k -> new ArrayList<>()).add(i);
        }

        // 按入参顺序输出,跳过软删/不存在
        List<UserProfileVO> out = new ArrayList<>(uniqueOrdered.size());
        for (Long userId : uniqueOrdered) {
            UserInfo u = userByUserId.get(userId);
            if (u == null) continue;
            UserProfileVO vo = userInfoConverter.toVO(u);
            List<UserInterestVO> interests =
                    interestConverter.toVOList(interestsByUser.getOrDefault(userId, Collections.emptyList()));
            vo.setInterests(interests);
            fillAvatarKeys(vo, u.getCustomAvatar());
            out.add(vo);
        }
        return out;
    }

    @Override
    public boolean updateProfile(long callerUserId, UpdateProfileRequest req) {
        if (callerUserId <= 0) {
            throw new BizException(ErrorCodes.UNAUTHENTICATED, "missing caller user id");
        }
        UserInfo patch = new UserInfo();
        patch.setUserId(callerUserId);

        boolean hasAny = false;
        if (req.hasNickname()) { patch.setNickname(req.getNickname()); hasAny = true; }
        if (req.hasAge())      { patch.setAge((short) req.getAge()); hasAny = true; }
        if (req.hasHeight())   { patch.setHeight((short) req.getHeight()); hasAny = true; }
        if (req.hasBio())      { patch.setBio(req.getBio()); hasAny = true; }
        if (req.hasOccupation()) { patch.setProfession(req.getOccupation()); hasAny = true; }
        if (req.hasEducation())  { patch.setEducation(req.getEducation()); hasAny = true; }
        if (req.hasLocation())   { patch.setPreferredLocation(req.getLocation()); hasAny = true; }

        if (!hasAny) {
            return true;
        }
        int rows = userInfoManager.updateDynamic(patch);
        return rows > 0;
    }

    @Override
    public UserProfileVO upsertOnboarding(long callerUserId, UpsertOnboardingRequest req) {
        if (callerUserId <= 0) {
            throw new BizException(ErrorCodes.UNAUTHENTICATED, "missing caller user id");
        }
        validateOnboarding(req);

        UserInfo existing = userInfoManager.getByUserId(callerUserId);
        if (existing == null) {
            throw new BizException(ErrorCodes.USER_NOT_FOUND, "user not found: " + callerUserId);
        }

        UserInfo patch = new UserInfo();
        patch.setUserId(callerUserId);
        patch.setNickname(req.getNickname());
        patch.setGender(GenderMapping.toDb(req.getGender()));
        patch.setBirthday(req.getBirthday());
        patch.setAge((short) req.getAge());
        patch.setHeight((short) req.getHeight());
        patch.setBio(req.getBio());
        patch.setProfession(req.getOccupation());
        patch.setEducation(req.getEducation());
        patch.setPreferredLocation(req.getLocation());
        patch.setPending(false);

        // user_type:UNSPECIFIED = 不覆盖现有值(登录链路创建时已默认 BH=2);
        // admin 灌入 DH 时显式传 USER_TYPE_DH(=1)覆盖。DB CHECK 兜底拒 0。
        Short userTypeDb = UserTypeMapping.toDb(req.getUserType());
        if (userTypeDb != null) {
            patch.setUserType(userTypeDb);
        }

        String defaultAvatarKey = req.getDefaultAvatarObjectKey();
        if (defaultAvatarKey != null && !defaultAvatarKey.isBlank()) {
            patch.setCustomAvatar(buildCustomAvatarJson(defaultAvatarKey));
        }

        txTemplate.executeWithoutResult(s -> userInfoManager.updateDynamic(patch));
        return getProfile(callerUserId);
    }

    private void validateOnboarding(UpsertOnboardingRequest req) {
        if (req.getNickname() == null || req.getNickname().isBlank()) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "nickname required");
        }
        if (req.getGender() == null || req.getGender() == Gender.GENDER_UNKNOWN) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "gender required");
        }
        if (req.getBirthday() == null || req.getBirthday().isBlank()) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "birthday required");
        }
    }

    // 解 custom_avatar JSONB,提取 originalKey + (可选) minKey/midKey/width/height,
    // 仅落 key 到 VO —— 头像走 CDN public,服务端不签 URL,App 自拼 ${cdnBaseUrl}/${bucket}/${key}。
    // 缩略图未生成时 minKey/midKey 回退到 originalKey,App 拿任一档都能展示。
    private void fillAvatarKeys(UserProfileVO vo, String customJson) {
        if (customJson == null || customJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = redisObjectMapper.readTree(customJson);
            String originalKey = textOrNull(root, "originalKey");
            if (originalKey == null || originalKey.isBlank()) {
                return;
            }
            String minKey = textOrNull(root, "minKey");
            String midKey = textOrNull(root, "midKey");
            UserProfileVO.AvatarVO avatar = new UserProfileVO.AvatarVO();
            avatar.setOriginalKey(originalKey);
            avatar.setMinKey(minKey != null && !minKey.isBlank() ? minKey : originalKey);
            avatar.setMidKey(midKey != null && !midKey.isBlank() ? midKey : originalKey);
            if (root.has("width") && root.get("width").isInt()) {
                avatar.setWidth(root.get("width").asInt());
            }
            if (root.has("height") && root.get("height").isInt()) {
                avatar.setHeight(root.get("height").asInt());
            }
            vo.setAvatar(avatar);
        } catch (JsonProcessingException e) {
            log.warn("custom_avatar JSON parse failed userId={}, raw={}", vo.getUserId(), customJson, e);
        }
    }

    private String buildCustomAvatarJson(String objectKey) {
        try {
            return redisObjectMapper.copy()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .writeValueAsString(new CustomAvatarPayload(objectKey));
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCodes.SYSTEM_ERROR, "failed to serialize custom_avatar", e);
        }
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return (n == null || n.isNull()) ? null : n.asText(null);
    }

    private record CustomAvatarPayload(String originalKey) {}
}
