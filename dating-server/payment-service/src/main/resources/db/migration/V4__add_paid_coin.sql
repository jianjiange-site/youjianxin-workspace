-- =============================================================================
-- V4: 金币双类别 — 免费金币 + 付费金币
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. coin_accounts 新增付费金币余额列
-- ---------------------------------------------------------------------------
ALTER TABLE coin_accounts
    ADD COLUMN paid_balance BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN coin_accounts.paid_balance IS '付费金币余额';

-- ---------------------------------------------------------------------------
-- 2. coin_ledger 新增付费金币变动相关列
-- ---------------------------------------------------------------------------
ALTER TABLE coin_ledger
    ADD COLUMN paid_amount        BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN paid_balance_after BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN coin_ledger.paid_amount        IS '付费金币变动量（正数）';
COMMENT ON COLUMN coin_ledger.paid_balance_after IS '变动后付费金币余额';
