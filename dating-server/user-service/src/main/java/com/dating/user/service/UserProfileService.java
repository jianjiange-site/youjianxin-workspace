package com.dating.user.service;

import com.dating.user.vo.UserProfileVO;
import com.jianjiange.proto.user.UpdateProfileRequest;
import com.jianjiange.proto.user.UpsertOnboardingRequest;

import java.util.Collection;
import java.util.List;

// 资料读写;读类带 (target) user_id,写类的 callerUserId 由 grpc/UserContext 注入。
// avatar / interests pic_url 等需要签 URL 的字段在 U7 实现 (本接口出参留空)。
public interface UserProfileService {

    UserProfileVO getProfile(long targetUserId);

    List<UserProfileVO> batchGetProfile(Collection<Long> targetUserIds);

    boolean updateProfile(long callerUserId, UpdateProfileRequest request);

    UserProfileVO upsertOnboarding(long callerUserId, UpsertOnboardingRequest request);
}
