package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 真人 like 操作归档(RIGHT_SWIPE / SUPER_HI 都落)。
 *
 * <p>语义偏离 docs §6.2 PRD"单向未回应暗恋":本表永不删,即时 match 形成也不清。
 * UI "Likes of me" 列表展示全部,客户端自行去重 / 打"已匹配" badge。
 *
 * <p>UNIQUE (from_user_id, to_user_id) 由 swipe 表层幂等已保证不重复 INSERT,DB 兜底。
 */
@Data
@TableName("like_record")
public class LikeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 点赞方 user_id(真人 BH) */
    private Long fromUserId;

    /** 被点赞方 user_id */
    private Long toUserId;

    /** SWIPE_RIGHT / SUPER_HI / DH_PLAN_ONLINE / DH_PLAN_OFFLINE;见 LikeSource 常量 */
    private String source;

    /** DH 计划生成 LIKE 时携带的文案;真人 swipe 留 NULL */
    private String likeContent;

    private OffsetDateTime likedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
