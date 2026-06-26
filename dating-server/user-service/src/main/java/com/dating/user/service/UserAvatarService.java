package com.dating.user.service;

import com.dating.user.service.dto.PresignAvatarResult;
import com.dating.user.vo.UserProfileVO;

// 头像上传:
//   1) presign:扩展名白名单 + size ≤ 10MB,返回 PUT URL + object_key (5min TTL)。
//   2) confirm:statObject 校验 + 落 custom_avatar JSONB + 删 user:profile:big:{id}。
// 缩略图 (min/mid) 暂用 originalKey,生成在后续切片中补。
public interface UserAvatarService {

    PresignAvatarResult presign(long callerUserId, String ext, long expectedSizeBytes);

    UserProfileVO.AvatarVO confirm(long callerUserId, String objectKey);
}
