package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 帖子计数底座(post_stats)。
 * <p>
 * 关键约束(post-service-design §5.3 / §6.2):
 * <ul>
 *   <li>**底座只存「已刷盘」部分**,不直接反映实时计数。</li>
 *   <li>实时值 = {@code likeCount} + Redis 增量 {@code <prefix>:post:stat:incr:{post_id}:likes}。</li>
 *   <li>读侧在内存里加和(写合并 / Write Coalescing)。</li>
 * </ul>
 */
@Data
@TableName("post_stats")
public class PostStat {

    @TableId(type = IdType.INPUT)
    private Long postId;

    private Integer likeCount;

    private Integer commentCount;

    private OffsetDateTime updatedAt;
}
