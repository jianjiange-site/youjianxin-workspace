package com.dating.mobilegateway.vo;

public record PostDetailVO(
        long postId,
        long userId,
        String content,
        java.util.List<String> imageKeys,
        int likeCount,
        int commentCount,
        boolean isLiked,
        long createdAtSeconds
) {}
