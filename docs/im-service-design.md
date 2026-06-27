# im-service 技术方案

> 配套：`match-service-prd-tech.md`（匹配后建会话 / 系统消息 / 在线信号源）、`payment-service-design.md`（聊天扣金币）、`user-service-design.md`（查 BH/DH 类型）、`ai-chat`（DH 自动回复 / 图片理解）。
>
> 本文基于 `dating-server/im-service` 当前代码现状（commit `youjianxin/ai-chat-im-payment-deploy`）整理，既是设计说明也是实现说明，新人对照代码即可上手。

## 1. 这个服务到底在干嘛

一句话：**im-service 是平台所有"即时通讯能力"的唯一收口层**。业务服务（user / match / ...）不直连 OpenIM、不直连 LiveKit，要发消息、要建会话、要拿 IM token、要看谁在线，全部走 im-service 的 gRPC（CLAUDE.md 红线 6）。

它干 6 件事：

| 能力 | 说白了就是 | 谁触发 |
|---|---|---|
| **回调收口** | OpenIM 每次发消息 / 用户上下线都会回调进来，im-service 决定"放行 / 拦截 / 改写"，并派生副作用 | OpenIM Server（经 mobile-gateway 透传） |
| **AI 自动回复** | 真人（BH）给数字人（DH）发消息，im-service 调 ai-chat 生成回复，模拟真人打字节奏分段发回去 | 消息回调（after-send） |
| **聊天扣费 + 反导流** | 真人发消息前扣金币；检测站外联系方式（微信/电话/ins）直接拦截 | 消息回调（before-send） |
| **在线状态** | 维护"谁在线 / 谁刚下线"，给 match-service 做 DH 模拟计划的信号源 | OpenIM 上下线回调 |
| **出站通知** | 匹配成功 fan-out、"正在输入"、系统消息等业务通知下发 | match-service / user-service 等 gRPC 调入 |
| **Token / 注册 / 通话** | 签发 OpenIM token、懒注册 IM 账号、签发 LiveKit 通话 token | mobile-gateway gRPC 调入 |

技术栈：Java 21 / Spring Boot 3.3.5 / gRPC 1.68.1 / JPA(Hibernate) + PostgreSQL / Redis / Nacos。

## 2. 上下游

```
                         OpenIM Server ──(HTTP 回调)──► mobile-gateway ──(gRPC OnRawCallback)──┐
                              ▲                                                                 │
                              │ OpenIM REST API                                                 ▼
   mobile-gateway ──gRPC──►  im-service  ◄──gRPC── match-service (SendMatchSuccess /
   (GetImToken /                  │                 EnsureConversation / 在线信号查询)
    GenerateCallToken)            │
                                  ├─►  ai-chat        (ChatAgent.chat 生成回复 / VisionAgent.understand 图片理解)
                                  ├─►  user-service   (查 from/to 的 user_type: BH / DH)
                                  ├─►  payment-service (聊天扣金币 ConsumeCoins / 余额预检 GetCoins)
                                  ├─►  OpenIM REST    (发消息 / 发业务通知 / 签 token / 注册)
                                  ├─►  Redis          (im:presence:online ZSet)
                                  └─►  PostgreSQL     (chat_messages / user_online_session)
```

**红线对齐**：

- 业务服务不直连 OpenIM / LiveKit，统一经 im-service（红线 6）。
- 服务间只用 gRPC（红线 3）；OpenIM 是外部中间件，走它自己的 REST API。
- 不直读别人的库 / Redis（红线 4）；反过来 im-service 的 `im:presence:online` / `user_online_session` 也**只通过 im-service 自己的 gRPC 对外**，match-service 不许直读（见 `match-service-prd-tech.md` §6.3）。
- 时间一律 UTC（红线 5）：`user_online_session` 用 `TIMESTAMPTZ`。

## 3. provider 抽象：为什么能换 IM 引擎

平台当前用 OpenIM，但代码里**没有把 OpenIM 写死**，而是抽了一层 provider 接口，未来换腾讯 IM 只加一个实现类即可。

两条链路都做了抽象：

**入站（解析回调）** —— `adaptor/ImProviderAdaptor`：

```java
public interface ImProviderAdaptor {
    boolean supports(String provider);     // 我处理不处理这个 provider？
    ImEvent parse(byte[] rawPayload);      // 把原始 JSON 回调解析成内部归一化事件
}
```

实现：`OpenImAdaptor`（已用）、`TencentImAdaptor`（占位）。`CallbackService` 拿到 `RawCallback(provider, payload)` 后，遍历所有 adaptor 找第一个 `supports(provider)==true` 的来解析，找不到就返回"unsupported"。

**出站（发消息）** —— `sender/MessageSender`：

```java
public interface MessageSender {
    boolean supports(String provider);
    boolean send(ImMessage msg);
}
```

实现：`OpenImSender`（调 `OpenImApiClient.sendMsg`）、`TencentImSender`（占位）。

> 关键设计：proto 契约（`im.proto`）是 **provider 中立**的 —— `OnRawCallbackResponse.code` 只表达业务决策（0=allow，非0=reject），**绝不**把 OpenIM 的 `errCode/nextCode` 泄进去。引擎专属码的翻译收敛在各 provider 的 gateway 入口。

## 4. 回调处理链路（核心）

OpenIM 在消息收发、用户上下线时会回调 webhook。这些回调先打到 mobile-gateway，gateway 把原始 body 用 `OnRawCallback(provider, payload)` 透传进 im-service。

### 4.1 总分发

`OnRawCallback` → `CallbackService`（选 adaptor 解析成 `ImEvent`）→ `ImEventDispatcher`（按事件类型分发）：

```java
switch (event) {
    case MessageSentEvent e        -> messageSentHandler.handle(e);   // after-send：记录 + AI 回复
    case MessageBeforeSendEvent e  -> beforeSendHandler.handle(e);    // before-send：扣费 + 反导流，可拒发
    case UserOnlineEvent e         -> presenceHandler.online(e);      // 上线
    case UserOfflineEvent e        -> presenceHandler.offline(e);     // 下线
    case UnknownEvent e            -> log + ok;                       // 不认识的回调，记日志放行
}
```

事件类型在 `model/event/` 下，是 sealed 风格的归一化模型（不暴露 OpenIM 字段）。

### 4.2 before-send：发消息前的"安检"（可拦截）

`BeforeSendHandler` 是唯一能**拒发**的 handler，OpenIM 拿到非 0 的 code 就不会把消息发出去。决策顺序（短路）：

```
1. 解析 senderId 失败            → allow（只记 warn，不因解析问题误伤用户）
2. senderId 是 DH（数字人）       → allow（AI 自己发的回复，不安检、不扣费）
3. 反导流开关开 && 是 TEXT 消息   → 命中站外联系方式正则 → REJECT 5002
4. 扣费开关开                     → 扣金币（见下）
```

拒发码（provider 中立业务码）：

| code | 含义 |
|---|---|
| 5002 | `REJECT_CONTACT_INFO` 检测到站外联系方式 |
| 5003 | `REJECT_INSUFFICIENT_COINS` 金币不足 |
| 5004 | `REJECT_PAYMENT_UNAVAILABLE` payment 服务故障（同步模式下 fail-close） |

**扣费两种模式**（`im.message.charge.async`，默认异步）：

- **同步模式**（`async=false`，老行为）：before-send 线程里直接 `paymentClient.consumeCoins()`，扣失败就拒发。payment 抖动会直接卡住发消息链路，所以是 fail-close。
- **异步模式**（`async=true`，默认）：before-send 只做**只读余额预检**（`getBalance()`，读路径快、deadline 短 800ms）：
  - 余额 < 单价 → 拒发 5003
  - 余额够 → **立即放行**，真正的扣减丢给 `CoinChargeDispatcher` 异步执行（幂等键 `im-msg:<messageId>`）

  好处：OpenIM 的回调有时间预算（约 5s），把"写扣减"挪出关键路径，发消息更快、更稳。代价：极端并发下可能有微小漏扣（接受）。

关键配置（`im.message.*`，Nacos 可热刷）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `charge.enabled` | true | 扣费总开关 |
| `charge.coin-cost` | 6 | 每条消息扣几个币（线上由 Nacos 覆盖） |
| `charge.async` | true | 异步扣 vs 同步扣 |
| `anti-funnel.enabled` | true | 反导流检测开关 |

### 4.3 after-send：消息已发出之后

`MessageSentHandler` 在消息已成功发出后触发，做两件事：**落库** + **决定要不要 AI 回复**。

先查 from / to 两端的 `user_type`（一次 `user-service` RPC），按四种路由分类：

| 路由 | 处理 |
|---|---|
| BH → BH | 只落库（真人之间，不介入） |
| **BH → DH** | 落库 + **调 ai-chat 生成回复，分段发回**（核心 AI 链路） |
| DH → BH | 只落库（AI 回复本身已经发出去了） |
| DH → DH | 异常情况，落库 + 跳过 |

**图片消息特殊处理**（BH→DH 且消息是 IMAGE）：

1. 取 `metadata[image_url]`（`OpenImAdaptor` 解析 OpenIM `PictureElem` 时按 缩略图→大图→原图 提取出来的）
2. 调 `VisionAgentGrpcClient.understand(imageUrl)` 拿到图片的自然语言描述
3. 拼成 `"[Image] <描述>"` 喂给 ai-chat，让 DH 能"看懂"图片再回复
4. 图片缺失 / 理解失败 → 回退文案 `"[Image] (couldn't be loaded...)"`，不阻断回复

## 5. AI 自动回复链路（拟真）

这是 im-service 最有"产品感"的部分：让数字人（DH）的回复像真人，而不是秒回一大段。

### 5.1 涉及的组件

| 组件 | 职责 |
|---|---|
| `AiChatGrpcClient` | 调 ai-chat 的 `ChatAgent.chat`，`threadId = fromUserId + ":" + toUserId`（LangGraph 会话记忆 key），故障返回 null |
| `VisionAgentGrpcClient` | 调 ai-chat 同进程的 `VisionAgent.understand` 做图片理解；vision 可降级（缺 key 时 UNIMPLEMENTED），失败返回 null。两个 client 复用同一个 `@GrpcClient("ai-chat")` 通道 |
| `DhTypingEmitter` | AI 生成期间持续向 BH 下发"正在输入" |
| `SentenceSplitter` | 把 AI 回复按句末标点切成多条 |
| `AiReplyDispatcher` | 编排：分段 + 按打字节奏延迟 + 逐条发送 + 落库 |

### 5.2 "正在输入"怎么做到拟真

`DhTypingEmitter` 用 try-with-resources 的 `Handle`：

```java
try (DhTypingEmitter.Handle ignored = dhTypingEmitter.start(dhUserId, bhUserId)) {
    aiReply = aiChatClient.chat(...);   // AI 生成期间一直续命 typing
}   // close() 自动下发一次"停止输入"
```

- 首帧先等一段**随机"阅读时间"**（`onset-delay-min/max-ms`，默认 2~5s）—— 模拟"DH 先读你的消息"。
- 之后每 `refresh-interval-ms`（默认 3s，必须 < 客户端 5s 兜底超时）续发一次 typing，AI 生成慢也不会"输入中"消失。
- `close()` 时下发一次停止信号。
- 多条回复之间用 `ping()` 单发一帧 typing（无需配套 stop）。

### 5.3 分段 + 打字节奏

`AiReplyDispatcher` 拿到整段 AI 回复后：

1. `SentenceSplitter.split(reply, maxMessages)` 按句末标点（`. ? ! 。 ？ ！ …`）切句；ASCII 小数点 `3.14` 有守卫不误切。
2. **达标才分段**：句数 ≥ `min-segments`（默认 3）且总字数 ≥ `min-split-chars`（默认 30）才分多条，否则合并成一条。超过 `max-messages`（默认 4）的尾句合并到最后一条。
3. 逐条发送，第 2 条起：先 `ping` typing → `sleep(按字数算的延迟)` → 再发。延迟 = `字数 × per-char-delay-ms`（默认 45ms/字），夹在 `[min-delay-ms 300, max-delay-ms 1500]`。
4. 整个分发**异步**跑在线程池里，回调线程立即返回（不占 OpenIM 回调预算）。
5. 每条都 `recorder.save(reply, "DH_BH")` 落库。messageId 规则：单条 `<原id>_ai`，多条 `<原id>_ai_<idx>`。

相关配置（`im.ai-reply.*` / `im.typing.*`）：`max-messages=4` / `min-segments=3` / `min-split-chars=30` / `per-char-delay-ms=45` / `min-delay-ms=300` / `max-delay-ms=1500` / `typing.refresh-interval-ms=3000` / `onset-delay 2000~5000`。

## 6. 聊天扣金币

`CoinChargeDispatcher` + `PaymentGrpcClient` 负责真正的扣减（异步模式下被 before-send 调用）。

- **线程池**：2 核心 / 512 有界队列，满了用 `CallerRunsPolicy` 由提交线程兜底执行（不丢任务）。
- **幂等键**：`im-msg:<messageId>` —— 同一条消息只扣一次（payment 侧靠 `coin_ledger` 的 unique 索引兜底，见 `payment-service-design.md` §6）。
- **重试**：`consumeCoins` 失败按 `FAILED` 重试若干次；`INSUFFICIENT`（余额不足）不重试，记 ERROR + 计数（消息已放行，接受这次漏扣）。

`PaymentGrpcClient` 把 payment 的返回码翻译成三态：

| 状态 | 触发 |
|---|---|
| `OK` | payment `code == 0` |
| `INSUFFICIENT` | payment `code == 3001`（余额不足） |
| `FAILED` | RPC 超时 / 故障 / 其它非 0 码 |

超时：写操作 `CALL_TIMEOUT_MS=2000`，只读预检 `READ_TIMEOUT_MS=800`（必须远小于 OpenIM 回调预算）。

## 7. 在线状态（presence）

im-service 是**全平台在线状态的唯一权威源**。OpenIM 在用户登录 / 登出时回调 `callbackUserOnlineCommand` / `callbackUserOfflineCommand`，im-service 据此维护两份数据：

| 存储 | 结构 | 装什么 | 用途 |
|---|---|---|---|
| Redis ZSet `im:presence:online` | member=userId(字符串), score=上线时刻(epoch ms) | 当前在线的实时态 | 谁刚上线 |
| PG `user_online_session` | 见 §10 表结构 | 上线/下线/时长的历史态 | 谁刚下线 |

> **为什么 DH 天然不在里面**：数字人没有 IM 设备登录，OpenIM 不会给它发上下线回调，所以两份数据**天然只含真人 BH**，match-service 那边省了一道"类型闸"。

### 7.1 上线 / 下线

`PresenceService.online()`：

```java
boolean first = redis.markOnline(userId, ts);   // ZADD NX（已在线则不覆盖 score）
if (first) recorder.openSession(uid, platform, toUtc(ts));   // 首次上线才开 PG 会话
```

`PresenceService.offline()`：

```java
Long since = redis.onlineSince(userId);          // ZSCORE 拿上线时刻
if (since != null) {
    long dur = (ts - since) / 1000;
    recorder.closeSession(uid, offlineAt, dur);  // 回填 offline_at + duration
    redis.remove(userId);                        // ZREM 移出在线集
}
```

注意 `ZADD NX`：一次会话内 score 固定为首次 `online_at`，**不随后续事件推高** —— 这正是 match-service `ListOnlineUserIds` 能"只命中本周期新上线用户"的基础。

### 7.2 孤儿会话清扫

只上线没下线（OpenIM 漏发下线回调 / 异常断连）的会话会变"孤儿"。`PresenceSweepJob`（`@Scheduled`，默认每 30 分钟）兜底：

```
找出 score < (now - max-online-hours) 的会话（默认 26h）
→ 按阈值封顶时长 closeSession(since + maxMs, maxMs/1000)
→ ZREM 移出在线集
```

配置 `im.presence.sweep.*`：`enabled=true` / `max-online-hours=26` / `cron="0 */30 * * * *"`。

### 7.3 对外两个查询 RPC（给 match-service 用）

| RPC | 实现 | 返回 |
|---|---|---|
| `ListOnlineUserIds(since, until, limit)` | `ZRANGEBYSCORE im:presence:online since until LIMIT 0 limit` | 本窗口新上线的真人 userId（按 online_at 升序） |
| `ListRecentOfflineUsers(since, until, limit)` | `SELECT user_id FROM user_online_session WHERE offline_at ∈ [since,until] AND NOT deleted ORDER BY user_id LIMIT n`（DISTINCT） | 本窗口已下线的真人 userId |

`limit` 都夹到 `[1, 50000]`，≤0 走默认 5000；非数字 userId（如 `imAdmin`）跳过。用途详见 `match-service-prd-tech.md` §6.3.1 / §6.3.2。

## 8. 出站通知

### 8.1 业务通知底座

`NotificationService` 封装 OpenIM 的 `/msg/send_business_notification`。`NotificationPayload` 接口约定：

```java
String key();                              // 业务类别，客户端据此分发
default boolean persist() { return false; } // 是否同时落库为消息
default int reliabilityLevel() { return 1; }// 1=仅在线推送  2=保证送达
default String toData(ObjectMapper m);       // 序列化成 JSON 字符串
```

`key` 常量（`NotificationKeys`）：`typing` / `match_success` / `match_welcome`。OpenIM 返回 `errCode==0` 视为成功，否则失败带 errMsg。

### 8.2 匹配成功 fan-out

`MatchSuccessNotifier.notifyMatchSuccess(matchId, a, b, matchedAt)`：

- 一次调用，向 **A、B 双方各下发一条** `match_success` 业务通知（`reliabilityLevel=2` 保证送达），客户端据此弹 "It's a Match!"。
- `participant.isDh()==true` 的一方**跳过不发**（数字人不需要弹窗），且不计入失败。
- 应发的非 DH 收件人全成功才返回 success。

payload 形如 `{matchId, self{userId,nickname,avatarKey,age}, peer{...}, matchedAt}`（`avatarKey` 是 object key 不是 URL，客户端自拼）。

## 9. Token / 注册 / 通话

| RPC | 干嘛 | 实现要点 |
|---|---|---|
| `GetImToken(userId, platform)` | 给客户端签 OpenIM 登录 token | 调 `OpenImApiClient.getUserToken`；**懒注册**：拿不到 token 就先 `registerUser` 再重试一次 |
| `RegisterImUser(userId, nickname, avatar)` | 建 OpenIM 账号 | 幂等：errMsg 含 "registered"/"exist" 也算成功 |
| `GenerateCallToken(userId, peerId)` | 1v1 音视频通话 token | `LiveKitTokenGenerator` 签 HS256 JWT，房间名 `call_<随机>`，claims 含 `roomJoin/canPublish/canSubscribe`，TTL 30min |

`OpenImApiClient` 用 admin 账号（`openim.admin-user-id` + `admin-secret`）调 OpenIM REST（`/auth/get_user_token`、`/user/user_register` 等）。

## 10. 数据存储

### 10.1 PostgreSQL（库 `dating_chat`，JPA `ddl-auto=update` 自动建表）

**`chat_messages`** —— 消息流水：

| 列 | 说明 |
|---|---|
| `message_id` (PK, String) | 全局消息 id（`openim_<serverMsgID>` 等多级兜底拼装） |
| `from_user_id` / `to_user_id` | 收发双方 |
| `content` (TEXT) | 内容 |
| `type` | 消息类型枚举（TEXT/IMAGE/...） |
| `conversation_type` / `provider` | 会话类型 / 引擎来源 |
| `route_type` | `BH_BH` / `BH_DH` / `DH_BH` / `DH_DH` |
| `timestamp` (Long, epoch 秒) / `created_at` | 时间 |

**`user_online_session`** —— 在线会话：

| 列 | 说明 |
|---|---|
| `id` (PK, auto) | |
| `user_id` | 真人 userId（数字） |
| `platform` | 登录平台 |
| `online_at` (TIMESTAMPTZ) | 上线时刻 |
| `offline_at` (TIMESTAMPTZ, nullable) | 下线时刻；NULL = 还在线 |
| `duration_seconds` | 在线时长 |
| `created_at` / `updated_at` / `deleted` | 审计 + 逻辑删除 |

> 建议补一个 partial index `(offline_at) WHERE offline_at IS NOT NULL AND NOT deleted` 给 `ListRecentOfflineUsers` 用（见 `match-service-prd-tech.md` §7.7）。当前靠 JPA 建表，未显式建该索引，量大后需补。

### 10.2 Redis

| Key | 类型 | TTL | 用途 |
|---|---|---|---|
| `im:presence:online` | ZSet | 永久（下线即 ZREM） | 在线用户集，member=userId / score=上线 ms |

## 11. 反导流：联系方式检测

`ContactInfoDetector.detect(content)` 按规则顺序匹配，命中返回规则名（`instagram`/`facebook`/`whatsapp`/`telegram`/`us_phone`），否则 null：

| 规则 | 抓什么 |
|---|---|
| instagram | `instagram.com/...`、`ig: @xxx` |
| facebook | `facebook.com/...`、`fb: @xxx` |
| whatsapp | `wa.me/...`、`whatsapp` |
| telegram | `t.me/...`、`tg: @xxx` |
| us_phone | 美国号码（`(123) 456-7890`、`+1 123-456-7890`，带前后边界守卫防误伤） |

命中即 before-send 拒发 5002。开关 `im.message.anti-funnel.enabled`。

## 12. 配置一览

| 项 | 值 / 默认 |
|---|---|
| HTTP 端口 | `IM_SERVICE_PORT` 默认 18080（health/actuator） |
| gRPC 端口 | `IM_GRPC_PORT` 默认 18081 |
| Nacos | `38.76.188.242:8848`，namespace `youjianxin-dating-dev`，`spring.config.import` 现代写法（`optional:` 前缀，不可达不阻塞启动） |
| PG | `dating_chat`，JPA `ddl-auto=update` |
| Redis | db 0，3s timeout |
| OpenIM | `openim.api-url` 默认 `http://127.0.0.1:10002`，admin 账号 `imAdmin` |
| LiveKit | api-key/secret-key（env 注入），TTL 30min |
| gRPC clients | `ai-chat` / `user-service` / `payment-service`，全走 `discovery:///` + plaintext |

> 凭据（OpenIM secret / LiveKit key / DB 密码）全部走 `${ENV}` 占位，真值放 Nacos 或环境变量，不进 git（CLAUDE.md 红线 1）。

## 13. 测试覆盖

`src/test` 已覆盖：`BeforeSendHandlerTest`（三大职责）、`MessageSentHandlerTest`（四路由 + 图片 + AI）、`ContactInfoDetectorTest`、`PresenceRedisManagerTest` / `PresenceServiceTest`、`OpenImAdaptorTest`（四类回调解析）、`NotificationServiceTest`、`MatchSuccessNotifierTest`（DH 跳过）。

## 14. 关键决策与取舍

- **扣费默认异步**：把"写扣减"挪出 OpenIM 回调关键路径，发消息更快更稳；代价是极端并发微小漏扣，可接受。
- **DH 消息不安检不扣费**：before-send 第一道就放行 DH，省 RPC、也避免给 AI 回复扣到真人头上。
- **AI 回复拟真三件套**（阅读延迟 + typing 续命 + 分段打字节奏）全部 Nacos 可调，上线看数据再调。
- **provider 中立**：回调响应码只表达业务决策，引擎专属码不外泄，为换 IM 引擎留口子。
- **在线信号收口到 im-service**：彻底废弃 user-service 的心跳链路，由 OpenIM 上下线回调被动驱动，跨服务消费一律走 gRPC（不直读 Redis/PG）。

---

**作者**：dating-server team / 2026-06-27
**Status**：与当前代码现状对齐（持续演进中；提现等 payment 侧能力见 `payment-service-design.md`）
