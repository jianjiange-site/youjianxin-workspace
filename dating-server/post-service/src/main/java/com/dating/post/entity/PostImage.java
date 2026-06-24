package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 帖子图片(post_images),联合主键 (post_id, sort_order)。
 * <p>
 * 设计意图(post-service-design §5.2):
 * 主键含 post_id,未来按 post_id 分区/分表时分区键已在 PK 里。
 * 只存对象存储 key,不存完整 URL(student-dev-guide §6.3)。
 */
@Data
@TableName("post_images")
public class PostImage {

    private Long postId;

    /** 0..8。 */
    private Integer sortOrder;

    /** 对象存储 key,格式 {@code post-image/{user_id}/{yyyymm}/{uuid}.{ext}}。 */
    private String imageKey;

    private OffsetDateTime createdAt;
}
