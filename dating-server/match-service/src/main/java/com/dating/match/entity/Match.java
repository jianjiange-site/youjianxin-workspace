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
 * 匹配关系(规范化 user_id_low &lt; user_id_high,一对人一行)。
 *
 * <p>UNIQUE (user_id_low, user_id_high) + CHECK (user_id_low &lt; user_id_high)
 * 保证 (A, B) 与 (B, A) 命中同一行,且并发重复 createMatch 由 DB 兜底。
 *
 * <p>触发 ON CONFLICT 是 ERROR 信号(召回过滤链路有 bug),见 MatchService.createMatch。
 */
@Data
@TableName("match")
public class Match {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** min(uid1, uid2) */
    private Long userIdLow;

    /** max(uid1, uid2) */
    private Long userIdHigh;

    private OffsetDateTime matchedAt;

    /** 入口动作来源:SWIPE_MATCH / SWIPE_SUPER_HI(见 MatchSource 常量) */
    private String source;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
