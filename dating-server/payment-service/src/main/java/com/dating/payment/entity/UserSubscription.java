package com.dating.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;

import java.time.OffsetDateTime;

/**
 * 用户订阅档位 + 到期时间。
 * 由 GetSubscription RPC 提供给 match-service 等服务读取(配额判定)。
 *
 * <p>每个用户在生效中只有一条记录(UNIQUE user_id WHERE deleted = false)。
 * 用户取消订阅 / 退款不删历史,只置 deleted = true。
 */
@TableName(value = "user_subscription")
public class UserSubscription {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 1=FREE 2=WEEKLY 3=MONTHLY 4=YEARLY,与 payment.proto SubscriptionTier 取值一致 */
    private Short tier;

    /** 到期时间(UTC);NULL 或 < NOW() → GetSubscription 返回 FREE */
    private OffsetDateTime expiresAt;

    /** 来源:IAP_APPLE / IAP_GOOGLE / TEST / ADMIN */
    private String source;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Short getTier() { return tier; }
    public void setTier(Short tier) { this.tier = tier; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
}
