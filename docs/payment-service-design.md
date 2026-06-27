# payment-service 技术方案

> 配套：`match-service-prd-tech.md`（订阅档位 / 配额 / SuperHi 扣金币）、`im-service-design.md`（聊天扣金币调 ConsumeCoins）。
>
> 本文基于 `dating-server/payment-service` 当前代码现状整理，既是设计也是实现说明。注意：部分能力（提现、IAP 票据校验、GetBalance）**当前是占位 `NOT_IMPLEMENTED`**，本文会逐处标注"已实现 / 待实现"，不夸大。

## 1. 这个服务在干嘛

一句话：**payment-service 管平台所有"钱"相关的事** —— 充值下单、第三方支付对接、金币账户、订阅档位、（未来的）提现。

四个模块，成熟度不一：

| 模块 | 干嘛 | 现状 |
|---|---|---|
| **支付下单** | 创建订单 → 拉起第三方支付 → 回调/校验确认 → 发奖励 | ✅ PayPal 全链路打通；Apple/Google/Stripe 仅枚举占位 |
| **金币** | 免费币 + 付费币双账户，查/加/扣，幂等 | ✅ 完整实现 |
| **订阅** | FREE/WEEKLY/MONTHLY/YEARLY 档位 + 到期判定 | ✅ 完整实现（给 match-service 配额用） |
| **提现** | 绑卡 → 申请 → 冻结 → 转账 | ⏳ 表结构有，service 全是 TODO 占位 |

技术栈：Java 21 / Spring Boot 3.3.5 / MyBatis-Plus / PostgreSQL（Flyway 管 schema）/ gRPC / Nacos。

## 2. 上下游 + 双协议设计

payment-service **同时提供 gRPC 和 REST 两套入口，但业务逻辑只写一份**：

```
   内部服务 (match / im) ──gRPC──►  PaymentGrpcService / CoinGrpcService ──┐
                                                                          ├─► Service 层 ─► Mapper ─► PostgreSQL
   App / Web / PayPal ──REST──► Controller ──GrpcAdapter（异步转同步）──────┘                      │
                                                                                    PaypalExecutor ─► PayPal REST API
```

**为什么 Controller 要经 `GrpcAdapter` 再回头调 gRPC Service Bean？** —— 为了复用同一份 gRPC 业务实现，REST 层不重写逻辑。`GrpcAdapter.invoke()` 把 gRPC 的 `StreamObserver` 异步回调用 `CompletableFuture` 包成同步阻塞调用（默认 10s 超时）：

```java
PaymentVerifyResponse resp = GrpcAdapter.invoke(obs ->
        paymentGrpcService.verifyPayment(req, obs));
```

> 例外：PayPal webhook 和 createOrder 需要 raw body / returnUrl 这类 proto 没定义的 Web 专属参数，Controller 直接调 Service，不绕 gRPC。

**调用方**：

- `match-service` → `GetSubscription`（查档位算配额）、`ConsumeCoins`（SuperHi 扣 100 币，幂等）。
- `im-service` → `GetCoins`（发消息前余额预检）、`ConsumeCoins`（聊天扣币，幂等键 `im-msg:<messageId>`）。
- App/Web → REST `/v1/*`（下单、查币、查订阅）。
- PayPal → REST webhook `/v1/payments/webhook/paypal`。

**红线对齐**：服务间只走 gRPC（红线 3）；凭据（PayPal client-secret、DB 密码、Nacos 密码）全 `${ENV}` 占位不进 git（红线 1）；时间 UTC（红线 5，`TIMESTAMPTZ` + V6 `SET TIME ZONE 'UTC'`）。

## 3. 数据库 schema（Flyway V1~V6）

库默认 `dating-chat`（带 dash，env 覆盖），Flyway 表 `flyway_history_payment`。MyBatis-Plus：`id-type=ASSIGN_ID`（雪花）、`@TableLogic deleted`。

### 3.1 钱包模块（V1，提现用，当前未启用业务）

**`user_wallets`** —— 法币钱包（提现用），每用户一行，乐观锁 `version`：

| 列 | 类型 | 说明 |
|---|---|---|
| `user_id` | BIGINT PK | |
| `balance` | NUMERIC(16,4) | 可用余额，CHECK ≥ 0 |
| `frozen_balance` | NUMERIC(16,4) | 冻结余额（提现中），CHECK ≥ 0 |
| `version` | INT | 乐观锁 |
| `created_at`/`updated_at` | TIMESTAMPTZ | |

**`user_wallet_entries`** —— 钱包流水（append-only 审计）：`entry_type ∈ {INCOME, WITHDRAW_FREEZE, WITHDRAW_SUCCESS, WITHDRAW_FAIL, ADMIN_ADJUST}`，记 `amount`/`before_balance`/`after_balance`/`order_id`（V2 从 `order_no` 改名对齐 proto）。

### 3.2 支付订单（V1 + V2 + V5）

**`payment_orders`**：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `user_id` | BIGINT | |
| `order_id` | VARCHAR(64) | 业务订单号，**唯一索引**（防重关键），V2 从 `order_no` 改名 |
| `product_id` | VARCHAR(128) | 内部商品 id / Apple ProductID |
| `amount` | NUMERIC(16,4) | 金额（元，不是分） |
| `currency` | VARCHAR(10) | 默认 USD |
| `payment_channel` | VARCHAR(32) | CHECK ∈ {APPLE_IAP, GOOGLE_BILLING, PAYPAL, STRIPE} |
| `status` | VARCHAR(20) | **状态机**，见下，V5 加了 GRANTED |
| `refund_status` | VARCHAR(20) | NONE / PARTIAL / FULL |
| `refunded_amount` | NUMERIC(16,4) | |
| `ext_transaction_id` | VARCHAR(128) | 第三方交易号（PayPal order id） |
| `notify_status` / `notify_count` | | 回调通知状态 |

**订单状态机**（V5 后）：

```
INIT ──支付成功(capture/webhook)──► PAID ──发奖完成──► GRANTED
  └──────────────失败─────────────► FAILED
```

- `INIT`：下单建好，等用户付款。
- `PAID`：第三方确认收款（webhook `PAYMENT.CAPTURE.COMPLETED` 或主动 capture 成功）。
- `GRANTED`：金币/订阅权益已发放（**幂等锚点**：发奖前先看是不是 GRANTED，是就跳过，防重复发币）。
- `FAILED`：支付失败。

### 3.3 提现记录（V1，表有逻辑空）

**`withdraw_records`**：`withdraw_no`(唯一) / `amount`(含手续费) / `fee` / `real_amount`(到账=amount-fee) / `payment_channel ∈ {PAYPAL,STRIPE,BANK}` / `channel_account`(收款账户) / `status ∈ {INIT,AUDITING,PROCESSING,SUCCESS,FAILED,REJECTED}`。

### 3.4 金币模块（V3 + V4）

**`coin_accounts`** —— 金币账户，每用户一行，乐观锁：

| 列 | 类型 | 说明 |
|---|---|---|
| `user_id` | BIGINT PK | |
| `balance` | BIGINT | **免费金币**余额，CHECK ≥ 0 |
| `paid_balance` | BIGINT | **付费金币**余额（V4 新增） |
| `version` | INT | 乐观锁 |

**`coin_ledger`** —— 金币流水（append-only）：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `user_id` | BIGINT | |
| `type` | VARCHAR(20) | CHECK ∈ {INCOME, EXPENSE} |
| `amount` / `balance_after` | BIGINT | **免费币**变动量 / 变动后余额 |
| `paid_amount` / `paid_balance_after` | BIGINT | **付费币**变动量 / 变动后余额（V4 新增） |
| `reason` | VARCHAR(255) | 变动原因 |
| `extra` | JSONB | 扩展 KV |
| `idempotency_key` | VARCHAR(64) | 幂等键（V6 新增），**部分唯一索引** `(user_id, idempotency_key) WHERE key IS NOT NULL` |

> **为什么金币分免费/付费两种**：免费币（活动赠送等）和付费币（真金白银买的）要分开记，便于财务对账、退款、合规。**消耗时先扣免费再扣付费**（见 §6）。

### 3.5 订阅（V6）

**`user_subscription`**：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `user_id` | BIGINT | **部分唯一索引** `(user_id) WHERE deleted=false`（生效中只一条） |
| `tier` | SMALLINT | 1=FREE 2=WEEKLY 3=MONTHLY 4=YEARLY，**取值对齐 `payment.proto` SubscriptionTier** |
| `expires_at` | TIMESTAMPTZ | 到期时间；NULL 或 < now() 视为过期 |
| `source` | VARCHAR(20) | IAP_APPLE / IAP_GOOGLE / TEST / ADMIN（审计） |
| `deleted` | BOOLEAN | 软删（取消/退款不删历史，置 true） |

## 4. 支付下单全流程（以 PayPal 为例）

当前 `CreateOrder` 只实现了 `PAYMENT_METHOD == PAYPAL` 分支，其它方式返回 `NOT_IMPLEMENTED`。

### 4.1 下单 CreateOrder

```
1. 生成内部订单号 orderId = "P" + 毫秒时间戳 + 6位随机
2. 从 InfoService 查商品价格（分）
3. PaypalExecutor.createOrder() → 调 PayPal /v2/checkout/orders（intent=CAPTURE）
     - 拿 PayPal access token（client_credentials）
     - body 带 custom_id=orderId（回调时认领订单的关键）
     - 解析返回：ext_order_id=PayPal order id，checkout_url=approval 链接
4. userId > 0 → 落 payment_orders（status=INIT, channel=PAYPAL, ext_transaction_id=PayPal order id）
5. 返回 {order_id, status=PENDING, ext_order_id, checkout_url}
```

App/Web 拿 `checkout_url` 跳转 PayPal 让用户付款。

### 4.2 收款确认（两条路，殊途同归）

**路 A：Webhook（被动，PayPal 主动推）** —— `POST /v1/payments/webhook/paypal`：

```
1. PaypalExecutor.handleWebhook()：验签（配了 webhook-id 才验；headers 不全则跳过验签）
2. 解析 event_type + custom_id(=我们的 orderId)
3. event_type == PAYMENT.CAPTURE.COMPLETED 且有 orderId：
     advanceStatusToPaid(orderId)  // INIT → PAID
     grantReward(orderId, "PayPal") // 发奖
4. event_type == CHECKOUT.ORDER.APPROVED：兜底自动 capture（幂等由 PayPal 保证）
```

**路 B：主动校验 VerifyPayment（客户端付完主动来确认）**：

```
PAYPAL 且有 ext_order_id：
  PaypalExecutor.captureOrder(extOrderId) → 调 PayPal .../capture
    - status==COMPLETED → PAID + grantReward
    - 遇 ORDER_ALREADY_CAPTURED（webhook 已先 capture）→ 当成功（幂等）
    - 否则 → FAILED（code 2002）
```

> 两条路都可能先到，靠**订单状态机 + GRANTED 幂等**保证奖励只发一次。

### 4.3 发奖 grantReward（幂等核心）

```java
order = 查 payment_orders;
if (order.status == "GRANTED") return;   // 已发过，直接跳过 ← 幂等闸

if (isSubscriptionProduct(productId)) {  // 订阅商品：先激活/续期订阅
    subscriptionService.activateSubscription(userId, tier, durationDays, source);
}
coinService.addPaidCoins(userId, coins, reason);  // 再发付费金币
order.status = "GRANTED";                // 标记发奖完成
```

商品定义在 `InfoService`（硬编码 `PRODUCT_DEFS`）：

| productId | 标题 | 价(分) | 金币 | 订阅 |
|---|---|---|---|---|
| 1~6 | 100/550/1150/2400/6250/13000 金币 | 99~9999 | 同名 | 无 |
| sub-weekly | 周卡 +1000付费币 | 999 | 1000 | tier=2, 7天 |
| sub-monthly | 月卡 +3000付费币 | 2999 | 3000 | tier=3, 30天 |
| sub-yearly | 年卡 +8000付费币 | 7999 | 8000 | tier=4, 365天 |

> 订阅商品既发付费金币、又升级订阅档位，体现"订阅送币"的产品设计。

## 5. PayPal 执行器细节

`PaypalExecutor` 封装所有 PayPal REST 交互，**与编排逻辑解耦**（持久化/幂等在 `PaymentService`）：

- **环境**：`sandbox`（默认）/ `live` 切 base url。
- **可降级**：`PAYPAL_CLIENT_ID/SECRET` 为空时 `configured=false`，**不让服务启动崩溃**；真正调用时才抛 `IllegalStateException`（其它通道/启动不受影响）。
- **鉴权**：每次调用前用 client_credentials 换 access token。
- **验签**：调 PayPal `/v1/notifications/verify-webhook-signature`；`webhook-id` 没配则跳过验签（仅 warn）。
- 错误码：createOrder 失败 2001 / capture 失败 2002 / 验签失败 2003 / webhook 处理失败 2004。

## 6. 金币模块（CoinService，完整实现）

**余额 = `balance`(免费) + `paid_balance`(付费)**，对外 `GetCoins` 返回两者之和。

| RPC | 逻辑 |
|---|---|
| `GetCoins` | 返回 `balance + paid_balance` |
| `GetCoinLedger` | 分页查流水，按 `created_at` 倒序 |
| `AddCoins` | 加**免费**币，乐观锁更新 account + 写 ledger(INCOME) |
| `AddPaidCoins` | 加**付费**币（充值/发奖走这个） |
| `ConsumeCoins` | 扣币：**先扣免费，免费不够再扣付费**；幂等 |

**ConsumeCoins 扣减顺序**：

```
if (balance >= amount)         // 免费币够
    freeTake=amount, paidTake=0
else                           // 免费币不够，付费币补
    freeTake=balance, paidTake=amount-balance
总额不足 → 返回 code 3001 Insufficient coins
```

**幂等设计**（V6，给 match SuperHi / im 聊天扣费防重发）：

```
1. idempotency_key 非空 → 先 findByIdempotencyKey 查历史，命中直接返上次结果（不重复扣）
2. 正常扣减 + 写 ledger（带 idempotency_key）
3. 并发竞态：两线程同时扣，写 ledger 撞 UNIQUE(user_id, idempotency_key)
   → catch DuplicateKeyException → 反查 existing → 返回上次结果
```

- match-service SuperHi 约定 key = `"superhi:" + client_request_id`；im-service 聊天扣费 key = `"im-msg:<messageId>"`。
- key 为空时**不走幂等**（兼容老调用方）。
- 乐观锁：account 更新带 `version` 条件，撞了抛 `RuntimeException` 触发上层重试。

`code 3001`（Insufficient）是 im-service `PaymentGrpcClient` 翻译成 `INSUFFICIENT` 的依据（见 `im-service-design.md` §6）。

## 7. 订阅模块（SubscriptionService，完整实现）

**`GetSubscription(userId)`**（给 match-service 算配额）：

```
查 user_subscription：
  无记录                          → FREE, is_active=false
  有但 expires_at < now 或 ≤FREE  → FREE, is_active=false（过期降级）
  有且 expires_at ≥ now 且 >FREE  → 原 tier, is_active=true, 带 expires_at
```

**`activateSubscription(userId, newTier, durationDays, source)`**（发奖时调）：

```
newTier ≤ FREE          → 忽略（不是付费档）
无记录                   → INSERT，expires_at = now + duration
未过期（续订）           → tier 只升不降，expires_at 从当前到期日顺延（叠加时长）
已过期                   → tier=newTier，expires_at 从 now 重新算
```

> "只升不降 + 时长顺延"：用户月卡没到期又买年卡，档位升到年卡、时间从原到期日往后加，不亏用户。

档位对应配额表见 `match-service-prd-tech.md` §3.1（FREE 5次右划/MONTHLY 15次 等）。

## 8. 提现模块（⏳ 待实现）

`WithdrawService` 三个方法当前全是 `NOT_IMPLEMENTED` 占位，表结构（`user_wallets` / `user_wallet_entries` / `withdraw_records`）已就位。`GetBalance`（InfoService）同样占位。

设计意图（TODO 注释里写明）：

```
Withdraw:  检查余额 → 冻结(balance→frozen) → 建 withdraw_records → 触发异步转账
GetHistory: 分页查 user_wallet_entries
BindAccount: 加密存储提现账户
```

REST 端点已挂好（`/v1/withdraw/accounts`、`/v1/withdraw/request`、`/v1/history`），只等填业务。

## 9. REST 端点一览

| 方法 | 路径 | 干嘛 | 状态 |
|---|---|---|---|
| POST | `/v1/payments/orders` | 创建订单 | ✅ PayPal |
| POST | `/v1/payments/verify` | 主动校验/capture | ✅ PayPal |
| POST | `/v1/payments/webhook` | 通用 webhook | ⏳ |
| POST | `/v1/payments/webhook/paypal` | PayPal webhook（raw body 验签） | ✅ |
| GET | `/v1/products` | 商品列表 | ✅ |
| GET | `/v1/balance` | 钱包余额 | ⏳ |
| POST | `/v1/subscription` | 查订阅 | ✅ |
| POST | `/v1/coins/balance`·`/ledger`·`/add`·`/consume` | 金币四件套 | ✅ |
| POST/GET | `/v1/withdraw/*`·`/history` | 提现 | ⏳ |

> `static/paypal/`（`index.html` + `app.js`）是 **PayPal 沙盒联调测试页**，本地拉起前端按钮跑通下单→付款→回调，不是生产页面。

## 10. 配置一览

| 项 | 值 / 默认 |
|---|---|
| REST 端口 | 8080 |
| gRPC 端口 | 9090（in-process name `payment-server`） |
| Nacos | `38.76.188.242:8848`，namespace `youjianxin-dating-dev`，`spring.config.import`（`optional:` 不阻塞启动） |
| PG | 默认 `dating-chat`，Hikari 池 max 10 |
| Flyway | `classpath:db/migration`，history 表 `flyway_history_payment`，`baseline-on-migrate` |
| MyBatis-Plus | 雪花 id、`@TableLogic deleted` |
| PayPal | client-id/secret（env，空=禁用通道）、environment `sandbox`、webhook-id |

> 凭据全 `${ENV}` 占位，不进 git（红线 1）。

## 11. 错误码 / 状态码速查

| code | 含义 |
|---|---|
| 0 | OK |
| 3001 | 金币不足（ConsumeCoins） |
| 2001/2002/2003/2004 | PayPal 下单/capture/验签/webhook 失败 |
| 4001 | 参数缺失（如 user_id） |
| `NOT_IMPLEMENTED` | 未实现的通道/接口 |

## 12. 关键决策与取舍

- **gRPC 与 REST 共用一份逻辑**：靠 `GrpcAdapter` 异步转同步，REST 层不重写业务，避免双份维护。
- **订单状态机 + GRANTED 幂等**：webhook 和主动 capture 两条确认路径可能都到，靠 `status==GRANTED` 锚点保证奖励只发一次。
- **金币分免费/付费双账户，扣减先免费后付费**：满足财务对账与退款/合规，对用户透明（对外只看总额）。
- **ConsumeCoins 幂等**：`idempotency_key` + 部分唯一索引 + 竞态兜底反查，让 match SuperHi / im 聊天扣费可安全重发。
- **PayPal 可降级**：缺凭据不崩服务，调用时才报错，方便本地/未配通道环境启动。
- **订阅只升不降 + 时长顺延**：续订/升档不亏用户。
- **提现先建表后填逻辑**：MVP 阶段表结构先稳定，业务后续补。

---

**作者**：dating-server team / 2026-06-27
**Status**：与当前代码现状对齐 —— 支付(PayPal)/金币/订阅已实现；提现、IAP 票据校验、GetBalance 待实现。
