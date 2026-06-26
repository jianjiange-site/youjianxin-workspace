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
 * Match 创建后副作用 outbox。
 *
 * <p>详见 dating-server/docs/match-service-prd-tech.md §5.3:
 * createMatch 在同一事务里写 match + outbox(action 三条:ENSURE_CONVERSATION / SYSTEM_MSG / DH_OPENING),
 * 后台 MatchOutboxRetry 异步消费 + 失败 exp backoff retry + 终态 DEAD 报警。
 *
 * <p>避免跨服务事务(CLAUDE.md 红线),代价是 IM 副作用最终一致(<= 几秒延迟)。
 */
@Data
@TableName(value = "match_outbox", autoResultMap = true)
public class MatchOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long matchId;

    /** ENSURE_CONVERSATION / SYSTEM_MSG / DH_OPENING(见 OutboxAction 常量) */
    private String action;

    /** 副作用调用所需的入参 JSON(双方 userId / matchId / dh 标识等) */
    private String payloadJson;

    /** 已重试次数 */
    private Integer attempts;

    /** 下次重试时间;exp backoff 时往后推 */
    private OffsetDateTime nextRetryAt;

    /** PENDING / DONE / DEAD(见 OutboxStatus 常量) */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
