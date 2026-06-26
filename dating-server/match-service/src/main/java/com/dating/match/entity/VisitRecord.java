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
 * 真人主页访问归档。
 *
 * <p>UNIQUE (from_user_id, to_user_id);UPSERT 同 (from,to) 累加 visit_count + 刷新 last_visited_at。
 * INSERT 由 {@code VisitRecordMapper.upsertVisit} 原生 SQL 完成,本实体只用于结果读取。
 */
@Data
@TableName("visit_record")
public class VisitRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 访问方 user_id(真人) */
    private Long fromUserId;

    /** 被访问方 user_id */
    private Long toUserId;

    /** 累计访问次数;UPSERT 每次 +1 */
    private Integer visitCount;

    /** PROFILE_VIEW / DH_PLAN_ONLINE / DH_PLAN_OFFLINE;见 VisitSource 常量 */
    private String source;

    /** 首次访问时间;INSERT 时设,UPDATE 不动 */
    private OffsetDateTime firstVisitedAt;

    /** 最近一次访问时间;UPSERT 时 NOW() */
    private OffsetDateTime lastVisitedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
