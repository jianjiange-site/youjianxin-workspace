package com.dating.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.post.entity.PostLike;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 点赞 Mapper。
 * <p>
 * upsert SQL 走 XML(PostgreSQL 特定语法 ON CONFLICT,见 design §9.3):
 * <pre>
 * INSERT INTO post_likes (user_id, post_id, status, created_at, updated_at)
 * VALUES (?, ?, ?, NOW(), NOW())
 * ON CONFLICT (user_id, post_id)
 * DO UPDATE SET status = EXCLUDED.status, updated_at = NOW()
 * WHERE post_likes.status &lt;&gt; EXCLUDED.status;
 * </pre>
 * 影响行数 0 表示已是目标状态(幂等),1 表示状态真变了(需要再加 Redis 增量)。
 */
@Mapper
public interface PostLikeMapper extends BaseMapper<PostLike> {

    /**
     * 幂等 upsert。返回 1 表示状态变化、0 表示无变化(目标状态已是 status)。
     *
     * @param userId 操作人
     * @param postId 被点赞的帖子
     * @param status 1 = 点赞 / 0 = 取消
     */
    int upsertStatus(@Param("userId") Long userId,
                     @Param("postId") Long postId,
                     @Param("status") int status);
}
