package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 评论(post_comments),预留楼中楼字段。
 * <p>
 * 升级楼中楼时数据库零改动(post-service-design §5.5):
 * <ul>
 *   <li>初期所有评论都是 {@code root_id = parent_id = reply_to_user_id = 0}。</li>
 *   <li>升级时只在 service 层把字段填好,读侧多查一次「该根评论下子评论」。</li>
 * </ul>
 */
@Data
@TableName("post_comments")
public class PostComment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long commentId;

    private Long postId;

    private Long userId;

    /** 根评论 ID;自身是根则 0。 */
    private Long rootId;

    /** 直接父评论 ID;自身是根则 0。 */
    private Long parentId;

    /** 被回复人 user_id;一级评论恒 0。 */
    private Long replyToUserId;

    private String content;

    private Integer status;

    @TableLogic
    private Integer deleted;

    private OffsetDateTime createdAt;
}
