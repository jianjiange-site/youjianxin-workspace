package com.dating.mobilegateway.vo;

public record CommentVO(
        long commentId,
        long postId,
        long userId,
        String content,
        long createdAtSeconds
) {}
