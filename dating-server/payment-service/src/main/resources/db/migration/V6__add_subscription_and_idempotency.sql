-- =====================================================================
-- V6__add_subscription_and_idempotency.sql
-- payment-service:
--   1. 新增 user_subscription 表(订阅档位 + 到期时间;服务于 match-service.GetSubscription)
--   2. coin_ledger 加 idempotency_key 列 + UNIQUE 索引(ConsumeCoins 幂等;match-service SuperHi 防重发)
--
-- 详见 dating-server/docs/match-service-prd-tech.md §3.1 配额表 / §6.7 与 payment-service 交互
-- =====================================================================

SET TIME ZONE 'UTC';

-- =====================================================================
-- 1. user_subscription  ---------- 用户订阅档位
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_subscription (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    -- 1=FREE 2=WEEKLY 3=MONTHLY 4=YEARLY,取值对齐 payment.proto SubscriptionTier(proto 1~4 与 DB 相同)
    tier         SMALLINT     NOT NULL,
    -- 到期时间;FREE 时无意义(可为 NULL)
    expires_at   TIMESTAMPTZ,
    -- 来源:IAP_APPLE / IAP_GOOGLE / TEST / ADMIN ...
    source       VARCHAR(20)  NOT NULL,
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 每个用户在生效中只有一条记录;软删后允许重新订阅
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_subscription_user
    ON user_subscription (user_id)
    WHERE deleted = false;

COMMENT ON TABLE  user_subscription            IS '用户订阅档位 + 到期时间(payment-service.GetSubscription 数据源,match-service 用于配额判定)';
COMMENT ON COLUMN user_subscription.tier       IS '档位:1=FREE 2=WEEKLY 3=MONTHLY 4=YEARLY;取值与 payment.proto SubscriptionTier 一致';
COMMENT ON COLUMN user_subscription.expires_at IS '到期时间(UTC);GetSubscription 时若 NULL 或 < NOW() 视为过期 → 返回 FREE';
COMMENT ON COLUMN user_subscription.source     IS '订阅来源:IAP_APPLE / IAP_GOOGLE / TEST / ADMIN;审计用';
COMMENT ON COLUMN user_subscription.deleted    IS '软删(MyBatis-Plus @TableLogic);用户取消订阅 / 退款不删历史只置 true';


-- =====================================================================
-- 2. coin_ledger  ---------- 加 idempotency_key 列 + UNIQUE 索引
-- =====================================================================
ALTER TABLE coin_ledger ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

-- 同一 (user_id, idempotency_key) 只能成功一次;非空才走幂等(兼容老 caller 不传 key 的场景)
CREATE UNIQUE INDEX IF NOT EXISTS uk_coin_ledger_idempotency
    ON coin_ledger (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN coin_ledger.idempotency_key
    IS 'ConsumeCoins 幂等 key;非空时同 (user_id, idempotency_key) 重发返回上次结果不重复扣;match-service SuperHi 用 "superhi:" + client_request_id';
