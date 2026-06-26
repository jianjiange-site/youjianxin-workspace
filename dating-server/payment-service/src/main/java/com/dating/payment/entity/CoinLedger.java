package com.dating.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.OffsetDateTime;
import java.util.Map;

@TableName(value = "coin_ledger", autoResultMap = true)
public class CoinLedger {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private Long amount;
    private Long paidAmount;
    private Long balanceAfter;
    private Long paidBalanceAfter;
    private String reason;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> extra;
    // 幂等 key:非空时同 (user_id, idempotency_key) 重发返回上次结果(见 V6__add_subscription_and_idempotency.sql)
    private String idempotencyKey;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public Long getPaidAmount() { return paidAmount; }
    public void setPaidAmount(Long paidAmount) { this.paidAmount = paidAmount; }
    public Long getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(Long balanceAfter) { this.balanceAfter = balanceAfter; }
    public Long getPaidBalanceAfter() { return paidBalanceAfter; }
    public void setPaidBalanceAfter(Long paidBalanceAfter) { this.paidBalanceAfter = paidBalanceAfter; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Map<String, String> getExtra() { return extra; }
    public void setExtra(Map<String, String> extra) { this.extra = extra; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
