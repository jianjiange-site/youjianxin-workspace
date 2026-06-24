package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.constant.LikeStatus;
import com.dating.post.entity.PostLike;
import com.dating.post.mapper.PostLikeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 点赞记录 upsert(post-service-design §9.3)。
 * <p>
 * 走 XML 写的 {@code ON CONFLICT (user_id, post_id) DO UPDATE} upsert。
 * 影响行数 0 = 已是目标状态(幂等),1 = 状态真变了(service 才去改 Redis 增量)。
 */
@Component
@RequiredArgsConstructor
public class PostLikeManager {

    private final PostLikeMapper postLikeMapper;

    /**
     * 幂等 upsert。
     *
     * @return true 状态变化(需要应用增量),false 已是目标状态(直接 return)
     */
    public boolean upsert(long userId, long postId, int targetStatus) {
        int rows = postLikeMapper.upsertStatus(userId, postId, targetStatus);
        return rows > 0;
    }

    /** 当前用户对该帖是否点过赞(详情接口回填 liked 字段用)。 */
    public boolean isLiked(long userId, long postId) {
        PostLike like = postLikeMapper.selectOne(
                new LambdaQueryWrapper<PostLike>()
                        .eq(PostLike::getUserId, userId)
                        .eq(PostLike::getPostId, postId)
                        .last("LIMIT 1")
        );
        return like != null && like.getStatus() != null && like.getStatus() == LikeStatus.LIKED;
    }
}
