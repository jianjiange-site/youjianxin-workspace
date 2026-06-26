package com.dating.mobilegateway.vo;

import java.util.List;

public record PostVO(
        long postId,
        long userId,
        String content,
        List<String> imageKeys,
        int likeCount,
        int commentCount,
        boolean isLiked,
        long createdAtSeconds
) {}
