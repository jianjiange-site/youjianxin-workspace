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
 * 划卡历史(权威记录)。
 *
 * <p>用途:
 * <ul>
 *   <li>召回阶段从中取 exclude_user_ids(已看过的不再出现)</li>
 *   <li>Swipe 接口幂等检查(同 user+target 第二次返回上次结果)</li>
 *   <li>D1 偏好建模(30 天 RIGHT/SUPER_HI 聚合 age/beauty/race 分布)</li>
 *   <li>BH 互划立即匹配:target_user_id=X AND direction=2 反查</li>
 * </ul>
 *
 * <p>UNIQUE (user_id, target_user_id) 保证幂等 + DB 兜底重复 swipe。
 */
@Data
@TableName("user_swipe_history")
public class UserSwipeHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long targetUserId;

    /** 1=BH 2=DH;来自 user-service.user_type */
    private Short targetUserType;

    /** 1=LEFT(不喜欢) 2=RIGHT(喜欢) 3=SUPER_HI */
    private Short direction;

    private OffsetDateTime swipedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
