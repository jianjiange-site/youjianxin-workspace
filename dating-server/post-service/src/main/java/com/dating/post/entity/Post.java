package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 帖子主表(posts)。
 * <p>
 * 设计约束(post-service-design §5.1):
 * <ul>
 *   <li>{@code id} 是内部物理主键,**不对外暴露**(红线 12)。</li>
 *   <li>{@code postId} 雪花 ID,跨库稳定的业务主键,所有 RPC 出参用它。</li>
 *   <li>主表故意不放图片、点赞数、评论数(写放大率高的字段拆出去)。</li>
 * </ul>
 */
@Data
@TableName("posts")
public class Post {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;

    private Long userId;

    private String content;

    /** 0=已删 / 1=正常 / 2=审核中,见 {@link com.dating.post.constant.PostStatus}。 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
