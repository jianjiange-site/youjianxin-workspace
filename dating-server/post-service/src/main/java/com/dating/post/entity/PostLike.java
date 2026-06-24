package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 点赞幂等记录(post_likes),联合主键 (user_id, post_id)。
 * <p>
 * 设计约束(post-service-design §5.4 / §9.3):
 * <ul>
 *   <li>**故意没有自增 ID**:联合主键既防重复点赞,又契合未来「按 user_id 分表」。</li>
 *   <li>**取消点赞 UPDATE status = 0 而不 DELETE**:复用同一行,避免 INSERT 冲突。</li>
 *   <li>Upsert SQL(ON CONFLICT)放在 {@code PostLikeMapper.xml}。</li>
 * </ul>
 */
@Data
@TableName("post_likes")
public class PostLike {

    private Long userId;

    private Long postId;

    /** 1=已赞 / 0=已取消,见 {@link com.dating.post.constant.LikeStatus}。 */
    private Integer status;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
