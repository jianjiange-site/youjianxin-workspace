# PayPal 支付接入文档

## 整体架构

```
Controller                    Service                     Executor
  ┌──────────────┐            ┌──────────────┐            ┌──────────────┐
  │ PaymentCtrl  │ ──routes──►│ PaymentSvc   │ ──method──►│ PaypalExec   │
  │              │            │              │  dispatches │              │
  │  /orders     │            │  createOrder │  PAYPAL ──►│  createOrder │
  │  /capture    │            │  capture     │  ────────►│  captureOrder│
  │  /webhook/   │            │  webhook     │  ────────►│  handleWebh. │
  │  paypal      │            │              │            │              │
  └──────────────┘            └──────────────┘            └──────────────┘
                                     │
                            ┌────────┴────────┐
                            │  其他 Executor   │
                            │ (Stripe / IAP…)  │
                            └─────────────────┘
```

## 新增文件总览

```
src/main/java/com/dating/payment/
├── executor/
│   └── paypal/
│       ├── PaypalExecutor.java     # PayPal 执行器 — 封装 PayPal REST API 调用
│       └── CaptureResult.java      # 捕获结果 POJO
├── controller/
│   └── PaymentController.java      # 新增 /payments/webhook/paypal、/capture 端点
├── service/
│   └── PaymentService.java         # 根据 PaymentMethod 路由到对应 Executor
└── config/
    └── RestTemplateConfig.java     # 新增 RestTemplate Bean

frontend_case/paypal/               # 前端 PayPal 支付 demo
├── index.html                      # PayPal JS SDK 按钮页面
└── app.js                          # createOrder + onApprove 逻辑
```

## 支付流程

```
App/前端                     payment-service                         PayPal
  │                              │                                      │
  │  POST /v1/payments/orders    │                                      │
  │  payment_method=PAYPAL       │                                      │
  ├─────────────────────────────►│                                      │
  │                              │  PaymentService.createOrder()        │
  │                              │  └─ PaymentMethod.PAYPAL             │
  │                              │     └─ PaypalExecutor.createOrder()  │
  │                              │        ├─ POST /v1/oauth2/token      │
  │                              │        ├─ POST /v2/checkout/orders   │
  │                              │        └─ 返回 paypalOrderId         │
  │  ← {client_secret,           │                                      │
  │      checkout_url}           │                                      │
  │                              │                                      │
  │  PayPal JS SDK 渲染按钮       │                                      │
  │  用户登录 → 批准订单          │                                      │
  │                              │                                      │
  │  onApprove(data)             │                                      │
  │  POST /v1/payments/orders/   │                                      │
  │       {id}/capture           │                                      │
  ├─────────────────────────────►│                                      │
  │                              │  PaymentService.capturePayPalOrder() │
  │                              │  └─ PaypalExecutor.captureOrder()    │
  │                              │     ├─ POST /v2/checkout/orders/     │
  │                              │     │         {id}/capture           │
  │                              │     └─ 返回 captureId, status        │
  │  ← {success, transaction_id} │                                      │
  │                              │                                      │
  │                              │  (异步) Webhook                      │
  │                              │  POST /v1/payments/webhook/paypal   │
  │                              │◄────────────────────────────────────┤
  │                              │  PaypalExecutor.handleWebhook()      │
  │                              │  ├─ 验签 (verify-webhook-signature)  │
  │                              │  └─ 处理 PAYMENT.CAPTURE.COMPLETED   │
```

## API 端点

| 端点 | 方法 | 说明 |
|---|---|---|
| `POST /v1/payments/orders` | `createOrder()` | `payment_method=PAYPAL` → 创建 PayPal 订单，返回 `client_secret`(PayPal order id) |
| `POST /v1/payments/orders/{paypalOrderId}/capture` | `capturePayPalOrder()` | 买家批准后捕获付款，返回 `success` + `transaction_id` |
| `POST /v1/payments/webhook/paypal` | `handlePayPalWebhook()` | PayPal 异步回调（带验签） |

## 三层职责

| 层级 | 位置 | 职责 | 不负责 |
|---|---|---|---|
| **Controller** | `controller/PaymentController.java` | 协议转换（JSON ↔ proto）、参数提取、路由分发 | 业务逻辑、DB 操作 |
| **Service** | `service/PaymentService.java` | 编排调度：按 `PaymentMethod` 路由到对应 Executor，订单持久化、幂等校验、权益发放 | 第三方 API 通信 |
| **Executor** | `executor/paypal/PaypalExecutor.java` | 封装 PayPal REST API：OAuth2 令牌、CreateOrder、Capture、Webhook 验签 | DB 操作、业务编排 |

## 配置

```yaml
paypal:
  client-id: ${PAYPAL_CLIENT_ID}
  client-secret: ${PAYPAL_CLIENT_SECRET}
  environment: ${PAYPAL_ENVIRONMENT:sandbox}   # sandbox | live
  webhook-id: ${PAYPAL_WEBHOOK_ID:}
```

`.env.local` 中已有（因文件已 gitignore，直接填入真实值）：

```bash
PAYPAL_CLIENT_ID=...
PAYPAL_CLIENT_SECRET=...
PAYPAL_ENVIRONMENT=sandbox
PAYPAL_WEBHOOK_ID=...
```

## Webhook 验签

PayPal 发送回调时会在 Header 中附带签名相关字段：

| Header | 说明 |
|---|---|
| `PayPal-Transmission-Id` | 唯一标识 |
| `PayPal-Transmission-Time` | 发送时间 |
| `PayPal-Transmission-Sig` | 签名 |
| `PayPal-Cert-Url` | 证书地址 |
| `PayPal-Auth-Algo` | 签名算法 |

`PaypalExecutor.handleWebhook()` 通过 `POST /v1/notifications/verify-webhook-signature` 验证签名有效性。

## 前端 Demo

`frontend_case/paypal/` 下提供了可直接运行的 HTML/JS demo：

1. 将 `index.html` 中 `PAYPAL_CLIENT_ID_PLACEHOLDER` 替换为真实 Sandbox Client ID
2. 用任意静态服务器（如 `npx serve`）托管该目录
3. 确保 payment-service 已启动在 `localhost:8080`
4. 注意跨域问题：本地开发时配置代理或后端加 `@CrossOrigin`

## 注意事项

| 注意点 | 说明 |
|---|---|
| **金额单位** | DB 用"分"，PayPal API 用"元" → Executor 内 `/ 100` 转换 |
| **幂等键** | 使用 `PayPal-Request-Id: {orderNo}` Header，防止重复下单 |
| **Webhook 验签** | `PAYPAL_WEBHOOK_ID` 未配置时跳过验签（仅限开发环境） |
| **SANDBOX vs LIVE** | `environment: sandbox` → `api-m.sandbox.paypal.com`；`live` → `api-m.paypal.com` |
| **前端跨域** | 本地开发时需处理 CORS，生产环境通过 nginx 反代同域访问 |
