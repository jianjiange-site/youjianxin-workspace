
#### 说明
stripe不支持中国运营主体，暂时废弃

#### checkout webhook事件
1. checkout.session.async_payment_failed
    - Occurs when a payment intent using a delayed payment method fails.

2. checkout.session.async_payment_succeeded
    - Occurs when a payment intent using a delayed payment method finally succeeds.

3. checkout.session.completed:
    - Occurs when a Checkout Session has been successfully completed.

4. checkout.session.expired:
    - Occurs when a Checkout Session is expired.

#### 测试密钥
1. 事件签名密钥：whsec_REDACTED
2. 密钥：sk_test_REDACTED
3. 公钥：pk_test_REDACTED