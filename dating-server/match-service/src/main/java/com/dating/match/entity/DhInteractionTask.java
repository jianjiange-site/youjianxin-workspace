package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * DH 模拟互动计划任务(短生命周期)。
 *
 * <p>详见 docs/match-service-prd-tech.md §6.3:
 * <ul>
 *   <li>OnlinePlanGenerator / OfflinePlanGenerator 批量写入</li>
 *   <li>LikeVisitorTaskExecutor 扫 execute_time 到期 → UPSERT like/visit_record → 硬删本行</li>
 *   <li>不软删、不审计;表内任意时刻只装"未执行 + 失败重试中"两类行</li>
 * </ul>
 */
@Data
@TableName("dh_interaction_task")
public class DhInteractionTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** DH user_id(发起方) */
    private Long fromUserId;

    /** 真人 BH user_id(接收方) */
    private Long toUserId;

    /** 1=LIKE 2=VISIT;见 {@link com.dating.match.constant.DhTaskAction} */
    private Short action;

    /** 1=ONLINE 2=OFFLINE;见 {@link com.dating.match.constant.DhTaskScene} */
    private Short scene;

    /** 计划执行时刻;executor 扫 WHERE execute_time <= NOW() 触发 */
    private OffsetDateTime executeTime;

    /** action=LIKE 时携带的文案;VISIT 为 NULL */
    private String likeContent;

    /** DB DEFAULT NOW();本表不需要 updated_at / deleted(硬删模型) */
    private OffsetDateTime createdAt;
}
