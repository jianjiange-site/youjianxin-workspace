-- =============================================================================
-- V5: 订单状态新增 GRANTED（奖励发放完成）
--     INIT → PAID → GRANTED
-- =============================================================================

ALTER TABLE payment_orders DROP CONSTRAINT IF EXISTS chk_payment_status;

ALTER TABLE payment_orders
    ADD CONSTRAINT chk_payment_status CHECK (status IN ('INIT', 'PAID', 'FAILED', 'GRANTED'));

COMMENT ON COLUMN payment_orders.status IS '订单状态: INIT(初始化), PAID(支付成功待发奖), FAILED(失败), GRANTED(奖励已发放)';
