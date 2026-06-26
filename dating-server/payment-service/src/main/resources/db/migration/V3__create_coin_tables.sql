-- =============================================================================
-- V3: 用户金币表 + 金币流水表
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. 用户金币表 (coin_accounts)
--    每用户一条记录，user_id 为主键。version 用于乐观锁。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS coin_accounts (
    user_id          BIGINT PRIMARY KEY,
    balance          BIGINT NOT NULL DEFAULT 0,
    version          INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_coin_balance_non_negative CHECK (balance >= 0)
);

COMMENT ON TABLE coin_accounts IS '用户金币账户';
COMMENT ON COLUMN coin_accounts.user_id IS '用户ID';
COMMENT ON COLUMN coin_accounts.balance IS '金币余额';
COMMENT ON COLUMN coin_accounts.version IS '乐观锁版本号';

-- ---------------------------------------------------------------------------
-- 2. 金币流水表 (coin_ledger)
--    Append-only 审计日志，任何金币变动都必须插入一条记录。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS coin_ledger (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    type             VARCHAR(20) NOT NULL,
    amount           BIGINT NOT NULL,
    balance_after    BIGINT NOT NULL,
    reason           VARCHAR(255) NOT NULL,
    extra            JSONB NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_ledger_type CHECK (type IN ('INCOME', 'EXPENSE'))
);

COMMENT ON TABLE coin_ledger IS '金币流水（Append-only）';
COMMENT ON COLUMN coin_ledger.type IS '变动类型: INCOME(收入), EXPENSE(消耗)';
COMMENT ON COLUMN coin_ledger.amount IS '变动金额（正数）';
COMMENT ON COLUMN coin_ledger.balance_after IS '变动后余额';
COMMENT ON COLUMN coin_ledger.reason IS '变动原因';
COMMENT ON COLUMN coin_ledger.extra IS '扩展信息（JSON KV）';

CREATE INDEX IF NOT EXISTS idx_coin_ledger_user_id ON coin_ledger(user_id);
CREATE INDEX IF NOT EXISTS idx_coin_ledger_created_at ON coin_ledger(created_at);
