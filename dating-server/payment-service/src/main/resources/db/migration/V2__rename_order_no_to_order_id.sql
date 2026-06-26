-- =============================================================================
-- V2: 统一字段命名 order_no → order_id
-- 说明: 与 proto 字段名对齐，避免命名不一致
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. user_wallet_entries 表
-- ---------------------------------------------------------------------------
ALTER TABLE user_wallet_entries
    RENAME COLUMN order_no TO order_id;

-- 重建索引
DROP INDEX IF EXISTS idx_wallet_entries_order_no;
CREATE INDEX IF NOT EXISTS idx_wallet_entries_order_id ON user_wallet_entries(order_id);

-- ---------------------------------------------------------------------------
-- 2. payment_orders 表
-- ---------------------------------------------------------------------------
ALTER TABLE payment_orders
    RENAME COLUMN order_no TO order_id;

COMMENT ON COLUMN payment_orders.order_id IS '业务系统唯一订单号（防重关键）';

-- 重建唯一索引
DROP INDEX IF EXISTS uidx_payment_orders_order_no;
CREATE UNIQUE INDEX IF NOT EXISTS uidx_payment_orders_order_id ON payment_orders(order_id);
