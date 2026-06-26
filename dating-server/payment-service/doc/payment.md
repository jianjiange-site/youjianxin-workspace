### 零、 服务架构概览

payment-service 采用三层架构，REST 和 gRPC 共享同一套 Service：

```
                    ┌──────────────────────────────┐
                    │    Controller (REST /v1/*)   │
                    │  InfoController              │
                    │  PaymentController           │
                    │  CoinController              │
                    │  WithdrawController          │
                    └──────────┬───────────────────┘
                               │ 直接方法调用（无 gRPC stub）
                    ┌──────────▼───────────────────┐
                    │        Service 层            │
                    │  InfoService  (读/查询)      │
                    │  PaymentService (支付写操作)  │
                    │  CoinService (金币系统)      │
                    │  WithdrawService (提现写操作) │
                    └──────────┬───────────────────┘
                               │
                    ┌──────────▼───────────────────┐
                    │   gRPC Service (port 9090)   │
                    │  PaymentGrpcService          │
                    │  CoinGrpcService             │
                    │  （外部微服务通过 gRPC 调用） │
                    └──────────────────────────────┘
```

- REST Controller **直接注入 Service**，不走 gRPC stub，无序列化开销
- gRPC Service 注入同一组 Service，供其他微服务调用
- Service 层操作 DB（MyBatis-Plus + Flyway）

---

### 一、 支付核心接口（PaymentService）

##### 1. POST /v1/payments/orders (创建支付订单)

- 请求参数： `user_id`, `product_id`, `payment_method`, `currency`, `platform`, `return_url`, `cancel_url`
- `user_id` 为必填，缺失时返回 HTTP 400（code=4001）
- 先调 PayPal API 创建订单（获取 `ext_order_id`），再写入 `payment_orders` 表（status=INIT）
- gRPC 通过 `CreateOrderRequest.user_id` 字段传递（proto 0.4.0+）

##### 2. POST /v1/payments/verify (票据校验 / 确认付款)

- PayPal 流程：前端传 `ext_order_id` + `payment_method=PAYPAL`，后端调 `captureOrder` 扣款
- capture 成功后：① `advanceStatusToPaid`（INIT → PAID） ② `grantReward`（发放付费金币 → 订单状态 PAID → GRANTED）
- verify 和 webhook 共享 `grantReward` 函数，先到先发，后到幂等跳过

##### 3. POST /v1/payments/webhook/paypal (PayPal 异步回调)

- 验签后处理事件：
  - `CHECKOUT.ORDER.APPROVED` → 自动 capture（幂等）
  - `PAYMENT.CAPTURE.COMPLETED` → `advanceStatusToPaid` + `grantReward`

##### 4. POST /v1/payments/webhook (通用 Webhook)

- 预留 Stripe 等渠道，当前返回 notImplemented

---

### 二、 金币系统（CoinService）

金币分为两类：**免费金币**（`balance` 列）和 **付费金币**（`paid_balance` 列）。对外只暴露总金币数，消耗优先扣免费。

##### 1. POST /v1/coins/balance (查询金币余额)

- 返回 `balance + paid_balance`（总金币数，不暴露分类）

##### 2. POST /v1/coins/ledger (分页查询金币流水)

- `amount` 和 `balance_after` 均为免费+付费的合计值

##### 3. POST /v1/coins/add (增加免费金币)

- 增加免费金币（`balance`），`paid_balance` 不变
- 流水记录 `paid_amount=0`

##### 4. POST /v1/coins/add-paid (增加付费金币) — gRPC: `AddPaidCoins`

- 增加付费金币（`paid_balance`），`balance` 不变
- 支付回调时由后端 `grantReward` 调用，不直接暴露给前端
- 流水记录 `amount=0`, `paid_amount=N`

##### 5. POST /v1/coins/consume (消耗金币)

- 校验 `balance + paid_balance >= amount`
- 优先扣免费金币，不够再扣付费金币
- 一条流水同时记录两边的 diff（`amount`、`paid_amount`）

---

### 三、 订单状态流转

```
createOrder  ──► INIT        (订单已创建，已写入 payment_orders)
verify 成功  ──► PAID        (PayPal 扣款成功，待发奖励)
webhook 成功 ──► PAID        (同上，advanceStatusToPaid 幂等)
grantReward  ──► GRANTED     (addPaidCoins 成功后，奖励发放完成)
```

| 状态 | 含义 | 触发 |
|---|---|---|
| `INIT` | 订单已创建，待支付 | `createOrder` |
| `PAID` | 支付成功，待发放奖励 | `verify` / `webhook` 确认 capture |
| `GRANTED` | 奖励已发放，订单终态 | `grantReward` → `addPaidCoins` 成功 |
| `FAILED` | 支付失败 | 表已支持，代码待实现 |

**幂等防重**：`grantReward` 检查 `status == GRANTED` → 已发放直接跳过。verify 和 webhook 无论谁先到，后到者都会幂等跳过。

**先发币后写状态**：`grantReward` 流程为 `addPaidCoins` → `update status=GRANTED`。若 addPaidCoins 成功但写 GRANTED 失败，重试会再发一次（少发比多发更难对账，因为 PayPal 已扣款）。

---

### 四、 WithdrawService — 提现接口

##### 1. POST /v1/withdraw/accounts (绑定提现账号)

- 用途： 记录用户接收资金的账户。
- 参数： type (paypal, bank_card, stripe_account), account_info (加密存储)。

##### 2. POST /v1/withdraw/request (申请提现)

- 用途： 发起提现申请。
- 参数： amount, account_id, currency (EUR)。
- 逻辑： 检查余额 -> 冻结资金 -> 插入提现记录 -> 触发异步转账任务。

##### 3. GET /v1/history (交易历史)

- 用途： 分页查询用户的钱包流水。
- 参数： page, size, type (可选过滤)。

---

### 五、 核心数据库表

##### 1. 支付订单表 (payment_orders)

```postgresql
CREATE TABLE payment_orders (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT NOT NULL,
    order_id           VARCHAR(64) NOT NULL,          -- 内部订单号（P 前缀）
    product_id         VARCHAR(128) NOT NULL,
    amount             NUMERIC(16, 4) NOT NULL,
    currency           VARCHAR(10) NOT NULL DEFAULT 'USD',
    payment_channel    VARCHAR(32) NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'INIT',
    refund_status      VARCHAR(20) NOT NULL DEFAULT 'NONE',
    refunded_amount    NUMERIC(16, 4) NOT NULL DEFAULT 0.0000,
    ext_transaction_id VARCHAR(128),                   -- PayPal order ID
    notify_status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    notify_count       INT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_payment_status CHECK (status IN ('INIT', 'PAID', 'FAILED', 'GRANTED')),
    CONSTRAINT chk_refund_status CHECK (refund_status IN ('NONE', 'PARTIAL', 'FULL')),
    CONSTRAINT chk_notify_status CHECK (notify_status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT chk_payment_channel CHECK (payment_channel IN ('APPLE_IAP', 'GOOGLE_BILLING', 'PAYPAL', 'STRIPE'))
);

CREATE UNIQUE INDEX uidx_payment_orders_order_id ON payment_orders(order_id);
CREATE INDEX idx_payment_orders_user_id ON payment_orders(user_id);
CREATE INDEX idx_payment_orders_ext_id ON payment_orders(ext_transaction_id);
```

##### 2. 金币账户表 (coin_accounts)

```postgresql
CREATE TABLE coin_accounts (
    user_id          BIGINT PRIMARY KEY,
    balance          BIGINT NOT NULL DEFAULT 0,       -- 免费金币
    paid_balance     BIGINT NOT NULL DEFAULT 0,       -- 付费金币
    version          INT NOT NULL DEFAULT 0,           -- 乐观锁
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_coin_balance_non_negative CHECK (balance >= 0)
);
```

##### 3. 金币流水表 (coin_ledger)

```postgresql
CREATE TABLE coin_ledger (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    type                VARCHAR(20) NOT NULL,          -- INCOME / EXPENSE
    amount              BIGINT NOT NULL,               -- 免费金币变动量
    paid_amount         BIGINT NOT NULL DEFAULT 0,     -- 付费金币变动量
    balance_after       BIGINT NOT NULL,               -- 变动后免费金币余额
    paid_balance_after  BIGINT NOT NULL DEFAULT 0,     -- 变动后付费金币余额
    reason              VARCHAR(255) NOT NULL,
    extra               JSONB NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_ledger_type CHECK (type IN ('INCOME', 'EXPENSE'))
);

CREATE INDEX idx_coin_ledger_user_id ON coin_ledger(user_id);
CREATE INDEX idx_coin_ledger_created_at ON coin_ledger(created_at);
```

##### 4. 用户钱包表 (user_wallets)

```postgresql
CREATE TABLE user_wallets (
    user_id          BIGINT PRIMARY KEY,
    balance          NUMERIC(16, 4) NOT NULL DEFAULT 0.0000,
    frozen_balance   NUMERIC(16, 4) NOT NULL DEFAULT 0.0000,
    version          INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_frozen_balance_non_negative CHECK (frozen_balance >= 0)
);
```

##### 5. 钱包流水变动表 (user_wallet_entries)

```postgresql
CREATE TABLE user_wallet_entries (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    order_id         VARCHAR(64) NOT NULL,
    entry_type       VARCHAR(32) NOT NULL,
    amount           NUMERIC(16, 4) NOT NULL,
    before_balance   NUMERIC(16, 4) NOT NULL,
    after_balance    NUMERIC(16, 4) NOT NULL,
    description      VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_entry_type CHECK (entry_type IN (
        'INCOME', 'WITHDRAW_FREEZE', 'WITHDRAW_SUCCESS',
        'WITHDRAW_FAIL', 'ADMIN_ADJUST'
    ))
);
```

##### 6. 提现记录表 (withdraw_records)

```postgresql
CREATE TABLE withdraw_records (
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
```

---

### 六、 PayPal 支付完整时序

```
App/前端                     payment-service                         PayPal
  │                              │                                      │
  │  POST /v1/payments/orders    │                                      │
  │  {user_id, product_id}       │                                      │
  ├─────────────────────────────►│                                      │
  │                              │  写入 payment_orders (INIT)          │
  │                              │  └─ PaypalExecutor.createOrder()     │
  │                              │     ├─ POST /v1/oauth2/token         │
  │                              │     ├─ POST /v2/checkout/orders      │
  │                              │     └─ 返回 paypalOrderId            │
  │  ← {order_id, checkout_url,  │                                      │
  │      ext_order_id}           │                                      │
  │                              │                                      │
  │  用户 PayPal 页面批准付款     │                                      │
  │                              │                                      │
  │  POST /v1/payments/verify    │                                      │
  │  {ext_order_id, order_id}    │                                      │
  ├─────────────────────────────►│                                      │
  │                              │  PaypalExecutor.captureOrder()       │
  │                              │  ├─ POST /v2/checkout/orders/{id}   │
  │                              │  │        /capture                  │
  │                              │  └─ COMPLETED                       │
  │                              │  advanceStatusToPaid(orderId)        │
  │                              │  grantReward(orderId)                │
  │                              │  ├─ addPaidCoins(userId, coins)      │
  │                              │  └─ status → GRANTED                │
  │  ← {status: PAID}            │                                      │
  │                              │                                      │
  │                              │  (异步) Webhook                      │
  │                              │  POST /v1/payments/webhook/paypal   │
  │                              │◄────────────────────────────────────┤
  │                              │  ├─ 验签                            │
  │                              │  ├─ CHECKOUT.ORDER.APPROVED         │
  │                              │  │   └─ auto-capture（幂等）         │
  │                              │  └─ PAYMENT.CAPTURE.COMPLETED       │
  │                              │      ├─ advanceStatusToPaid（幂等）  │
  │                              │      └─ grantReward（幂等跳过）      │
```

---

### 七、 给后端研发的特别建议

1. **幂等性**：`grantReward` 检查 `status == GRANTED` 防重。verify 和 webhook 无论谁先到，后到者幂等跳过。
2. **金币分类**：免费金币（`balance`）和付费金币（`paid_balance`）在 DB 层分开存储，消耗优先扣免费。对外只暴露总金币数。
3. **先发币后写状态**：`grantReward` 中 `addPaidCoins` 先于 `update status=GRANTED`，避免状态已写但币未发的漏发风险。
4. **统一金额单位**：接口传输一律使用"分"。€ 6.95 传输 695，避免浮点数精度问题。
5. **分层职责**：Controller 只做协议转换（JSON ↔ Protobuf），Service 层做业务逻辑 + DB 操作。新增外部入口（如 MQ 消费）也复用 Service，不重复写逻辑。
6. **Proto 版本**：proto 仓库独立管理，版本号语义化（MAJOR.MINOR.PATCH）。新增字段/RPC → MINOR bump。禁止使用 SNAPSHOT 于生产环境。
