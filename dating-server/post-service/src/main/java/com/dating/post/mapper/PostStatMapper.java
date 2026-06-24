package com.dating.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.post.entity.PostStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 计数底座 Mapper。
 * <p>
 * 刷盘走「增量加法」而不是覆盖,Job 之间乱序、重叠都不影响结果(design §9.4)。
 */
@Mapper
public interface PostStatMapper extends BaseMapper<PostStat> {

    /**
     * 增量加点赞数(LikeFlushJob 用)。
     *
     * @param postId 业务主键
     * @param delta  这一批的累计增量(可正可负),Lua 原子取走 + 归零后传入
     * @return 影响行数(0 表示该 post_id 的 post_stats 行还没建)
     */
    @Update("UPDATE post_stats SET like_count = like_count + #{delta}, "
            + "updated_at = NOW() WHERE post_id = #{postId}")
    int incrLikeCount(@Param("postId") Long postId, @Param("delta") int delta);

    /** 增量加评论数(CommentFlushJob 用)。 */
    @Update("UPDATE post_stats SET comment_count = comment_count + #{delta}, "
            + "updated_at = NOW() WHERE post_id = #{postId}")
    int incrCommentCount(@Param("postId") Long postId, @Param("delta") int delta);
}
