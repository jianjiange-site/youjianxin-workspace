package com.dating.user.service.impl;

import com.dating.common.storage.ObjectNotFoundException;
import com.dating.common.storage.ObjectStorage;
import com.dating.user.constant.AvatarExt;
import com.dating.user.exception.BizException;
import com.dating.user.exception.ErrorCodes;
import com.dating.user.manager.UserInfoManager;
import com.dating.user.service.UserAvatarService;
import com.dating.user.service.dto.PresignAvatarResult;
import com.dating.user.vo.UserProfileVO;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

// Avatar 上传 service:
//   Presign:校验 ext + size (≤10MB),objectKey=avatar/{userId}/{uuid}.{ext},
//           调 ObjectStorage.presignedPutUrl 拿 5min TTL URL。
//   Confirm:校验 objectKey 前缀 + headObjectSize 拿真实 size,落 custom_avatar JSONB,
//           删 user:profile / user:profile:big 缓存。返回 key-only AvatarVO(App 自拼 CDN URL)。
@Slf4j
@Service
public class UserAvatarServiceImpl implements UserAvatarService {

    private final UserInfoManager userInfoManager;
    private final ObjectStorage objectStorage;
    private final ObjectMapper redisObjectMapper;

    public UserAvatarServiceImpl(
            UserInfoManager userInfoManager,
            ObjectStorage objectStorage,
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper) {
        this.userInfoManager = userInfoManager;
        this.objectStorage = objectStorage;
        this.redisObjectMapper = redisObjectMapper;
    }

    @Override
    public PresignAvatarResult presign(long callerUserId, String ext, long expectedSizeBytes) {
        if (callerUserId <= 0) {
            throw new BizException(ErrorCodes.UNAUTHENTICATED, "missing caller user id");
        }
        if (!AvatarExt.isAllowed(ext)) {
            throw new BizException(ErrorCodes.AVATAR_EXT_NOT_ALLOWED,
                    "avatar ext not allowed: " + ext);
        }
        if (expectedSizeBytes <= 0 || expectedSizeBytes > AvatarExt.MAX_BYTES) {
            throw new BizException(ErrorCodes.AVATAR_SIZE_EXCEEDED,
                    "expected_size_bytes out of range: " + expectedSizeBytes);
        }
        String objectKey = "avatar/" + callerUserId + "/" + UUID.randomUUID() + "." + ext.toLowerCase();
        String url = objectStorage.presignedPutUrl(objectKey);
        long expiresAtMs = Instant.now().plusSeconds(5 * 60).toEpochMilli();
        return new PresignAvatarResult(url, objectKey, expiresAtMs);
    }

    @Override
    public UserProfileVO.AvatarVO confirm(long callerUserId, String objectKey) {
        if (callerUserId <= 0) {
            throw new BizException(ErrorCodes.UNAUTHENTICATED, "missing caller user id");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new BizException(ErrorCodes.AVATAR_OBJECT_KEY_INVALID, "object_key required");
        }
        String expectedPrefix = "avatar/" + callerUserId + "/";
        if (!objectKey.startsWith(expectedPrefix)) {
            throw new BizException(ErrorCodes.AVATAR_OBJECT_KEY_INVALID,
                    "object_key not owned by caller: " + objectKey);
        }
        long actualSize;
        try {
            actualSize = objectStorage.headObjectSize(objectKey);
        } catch (ObjectNotFoundException nf) {
            throw new BizException(ErrorCodes.AVATAR_OBJECT_NOT_FOUND,
                    "uploaded object not found: " + objectKey, nf);
        }
        if (actualSize <= 0 || actualSize > AvatarExt.MAX_BYTES) {
            throw new BizException(ErrorCodes.AVATAR_SIZE_EXCEEDED,
                    "uploaded object size out of range: " + actualSize);
        }
        String json = toCustomAvatarJson(objectKey);
        userInfoManager.updateCustomAvatar(callerUserId, json);

        // 往 颜值分计算的 topic 里发一个消息 消息体 userID 文件的 key

        // 缩略图未生成时 min/mid 回退 original;App 侧自拼 ${cdnBaseUrl}/${bucket}/${key}。
        UserProfileVO.AvatarVO vo = new UserProfileVO.AvatarVO();
        vo.setOriginalKey(objectKey);
        vo.setMinKey(objectKey);
        vo.setMidKey(objectKey);
        return vo;
    }

    private String toCustomAvatarJson(String objectKey) {
        try {
            return redisObjectMapper.copy()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .writeValueAsString(new CustomAvatar(objectKey));
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCodes.SYSTEM_ERROR,
                    "failed to serialize custom_avatar", e);
        }
    }

    // 简化 JSONB 形态;后续切片补 min/mid/width/height。
    private record CustomAvatar(String originalKey) {}
}
