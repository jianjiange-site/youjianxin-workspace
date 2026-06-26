-- =============================================================================
-- V1: 支付核心表初始化
-- 说明: 钱包、流水、支付订单、提现记录四张核心表
-- 参考: payment-service/payment.md §三
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. 用户钱包表 (user_wallets)
--    每用户一条记录，user_id 为主键。version 用于乐观锁。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_wallets (
    user_id          BIGINT PRIMARY KEY,
    balance          NUMERIC(16, 4) NOT NULL DEFAULT 0.0000,
    frozen_balance   NUMERIC(16, 4) NOT NULL DEFAULT 0.0000,
    version          INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_frozen_balance_non_negative CHECK (frozen_balance >= 0)
);

COMMENT ON TABLE user_wallets IS '用户钱包';
COMMENT ON COLUMN user_wallets.user_id IS '用户ID';
COMMENT ON COLUMN user_wallets.balance IS '可用余额';
COMMENT ON COLUMN user_wallets.frozen_balance IS '冻结余额（提现中/冻结中）';
COMMENT ON COLUMN user_wallets.version IS '乐观锁版本号';

-- ---------------------------------------------------------------------------
-- 2. 钱包流水变动表 (user_wallet_entries)
--    Append-only 审计日志，任何余额变动都必须插入一条记录。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_wallet_entries (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    order_no         VARCHAR(64) NOT NULL,
    entry_type       VARCHAR(32) NOT NULL,
    amount           NUMERIC(16, 4) NOT NULL,
    before_balance   NUMERIC(16, 4) NOT NULL,
    after_balance    NUMERIC(16, 4) NOT NULL,
    description      VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_entry_type CHECK (entry_type IN (
        'INCOME',             -- 充值/收入
        'WITHDRAW_FREEZE',    -- 提现冻结
        'WITHDRAW_SUCCESS',   -- 提现扣减
        'WITHDRAW_FAIL',      -- 提现解冻返还
        'ADMIN_ADJUST'        -- 后台调账
    ))
);

COMMENT ON TABLE user_wallet_entries IS '钱包流水变动（Append-only）';
COMMENT ON COLUMN user_wallet_entries.entry_type IS '变动类型: INCOME, WITHDRAW_FREEZE, WITHDRAW_SUCCESS, WITHDRAW_FAIL, ADMIN_ADJUST';
COMMENT ON COLUMN user_wallet_entries.amount IS '变动金额（正数加钱，负数扣钱）';
COMMENT ON COLUMN user_wallet_entries.before_balance IS '变动前可用余额';
COMMENT ON COLUMN user_wallet_entries.after_balance IS '变动后可用余额';

CREATE INDEX IF NOT EXISTS idx_wallet_entries_user_id ON user_wallet_entries(user_id);
CREATE INDEX IF NOT EXISTS idx_wallet_entries_order_no ON user_wallet_entries(order_no);

-- ---------------------------------------------------------------------------
-- 3. 支付订单表 (payment_orders)
--    用户通过第三方渠道（Apple/Google/Stripe/PayPal）充值订单。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_orders (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT NOT NULL,
    order_no           VARCHAR(64) NOT NULL,
    product_id         VARCHAR(128) NOT NULL,
    amount             NUMERIC(16, 4) NOT NULL,
    currency           VARCHAR(10) NOT NULL DEFAULT 'USD',
    payment_channel    VARCHAR(32) NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'INIT',
    refund_status      VARCHAR(20) NOT NULL DEFAULT 'NONE',
    refunded_amount    NUMERIC(16, 4) NOT NULL DEFAULT 0.0000,
    ext_transaction_id VARCHAR(128),
    notify_status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    notify_count       INT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_payment_status CHECK (status IN ('INIT', 'PAID', 'FAILED')),
    CONSTRAINT chk_refund_status CHECK (refund_status IN ('NONE', 'PARTIAL', 'FULL')),
    CONSTRAINT chk_notify_status CHECK (notify_status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT chk_payment_channel CHECK (payment_channel IN ('APPLE_IAP', 'GOOGLE_BILLING', 'PAYPAL', 'STRIPE'))
);

COMMENT ON TABLE payment_orders IS '支付订单';
COMMENT ON COLUMN payment_orders.order_no IS '业务系统唯一订单号（防重关键）';
COMMENT ON COLUMN payment_orders.product_id IS '内部商品ID / Apple ProductID';
COMMENT ON COLUMN payment_orders.amount IS '支付金额';
COMMENT ON COLUMN payment_orders.payment_channel IS '支付渠道: APPLE_IAP, GOOGLE_BILLING, PAYPAL, STRIPE';
COMMENT ON COLUMN payment_orders.status IS '订单状态: INIT(初始化), PAID(支付成功), FAILED(支付失败)';
COMMENT ON COLUMN payment_orders.refund_status IS '退款状态: NONE(未退款), PARTIAL(部分退款), FULL(全额退款)';

CREATE UNIQUE INDEX IF NOT EXISTS uidx_payment_orders_order_no ON payment_orders(order_no);
CREATE INDEX IF NOT EXISTS idx_payment_orders_user_id ON payment_orders(user_id);
CREATE INDEX IF NOT EXISTS idx_payment_orders_ext_id ON payment_orders(ext_transaction_id);

-- ---------------------------------------------------------------------------
-- 4. 提现记录表 (withdraw_records)
--    用户将钱包余额提现到第三方渠道的记录。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS withdraw_records (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT NOT NULL,
    withdraw_no        VARCHAR(64) NOT NULL,
    amount             NUMERIC(16, 4) NOT NULL,
    fee                NUMERIC(16, 4) NOT NULL DEFAULT 0.0000,
    real_amount        NUMERIC(16, 4) NOT NULL,
    currency           VARCHAR(10) NOT NULL DEFAULT 'USD',
    payment_channel    VARCHAR(32) NOT NULL,
    channel_account    VARCHAR(128) NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'INIT',
    ext_transaction_id VARCHAR(128),
    fail_reason        VARCHAR(255),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_withdraw_status CHECK (status IN ('INIT', 'AUDITING', 'PROCESSING', 'SUCCESS', 'FAILED', 'REJECTED')),
    CONSTRAINT chk_withdraw_channel CHECK (payment_channel IN ('PAYPAL', 'STRIPE', 'BANK'))
);

COMMENT ON TABLE withdraw_records IS '提现记录';
COMMENT ON COLUMN withdraw_records.withdraw_no IS '提现业务唯一单号';
COMMENT ON COLUMN withdraw_records.amount IS '申请提现总金额（含手续费）';
COMMENT ON COLUMN withdraw_records.fee IS '渠道或平台手续费';
COMMENT ON COLUMN withdraw_records.real_amount IS '用户实际到账金额 (amount - fee)';
COMMENT ON COLUMN withdraw_records.payment_channel IS '提现渠道: PAYPAL, STRIPE, BANK';
COMMENT ON COLUMN withdraw_records.channel_account IS '用户收款账户（邮箱/银行卡号）';
COMMENT ON COLUMN withdraw_records.status IS '提现状态: INIT, AUDITING, PROCESSING, SUCCESS, FAILED, REJECTED';

CREATE UNIQUE INDEX IF NOT EXISTS uidx_withdraw_records_no ON withdraw_records(withdraw_no);
CREATE INDEX IF NOT EXISTS idx_withdraw_records_user_id ON withdraw_records(user_id);
