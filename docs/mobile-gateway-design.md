# mobile-gateway 技术方案

> 状态：草案 v0.1（待 review）
> 范围：仅覆盖技术选型与服务设计；部署细节遵循 `docs/deploy.md`，编码规范遵循 `CLAUDE.md`。
> 关联：鉴权域建表脚本（含每字段 COMMENT）见 `docs/sql/mobile-gateway.sql`。

## 1. 背景与定位

移动端（RN App）需要一个稳定的对外入口，把 REST 翻译成 gRPC 调用下游各业务服务。本服务承担这个角色。

**定位：REST → gRPC 的 BFF（Backend for Frontend）网关**，不是纯路由透传型网关。理由：

- 内部全是 gRPC，没有"下游 HTTP 服务"可路由，Spring Cloud Gateway / Kong 这类纯路由网关的优势用不上。
- 移动端接口通常需要聚合多个服务（例：首页用户卡片 = `user-service` + `relation` + `im` 在线状态），BFF 模式天然适合。
- 字段裁剪、版本适配、协议翻译在网关一处收口，下游 gRPC 服务保持纯净（不感知 HTTP / 移动端字段风格）。

服务名：**`mobile-gateway`**。后续若有 H5 / 第三方接入，再独立 BFF（例如 `web-bff` / `open-api-gateway`），避免 BFF 膨胀成"什么都管"。

## 2. 架构

```
        ┌──────────────┐  HTTPS / REST + JWT
RN App ─┤  Nginx (TLS) ├──────────────────┐
        └──────────────┘                  │
                                          ▼
                       ┌─────────────────────────────────────┐
                       │   mobile-gateway                     │
                       │  (Spring Boot 3.3.5 / MVC + Loom)    │
                       │  - JWT 签发 / 验签 / 黑名单          │
                       │  - 账号 / 设备 / refresh token 持久化│
                       │  - 限流 / CORS / traceId 注入        │
                       │  - REST↔gRPC 协议转换                │
                       │  - BFF 聚合 / 字段裁剪               │
                       └─────────┬─────────────────┬──────────┘
                                 │                 │
                       PG(account/device/token)    │ gRPC (Nacos discovery)
                                                   │
                  ┌──────────┬─────────────────────┼───────────┬──────────┐
                  ▼          ▼                     ▼           ▼          ▼
            user-service     im-service          relation-svc  media-svc   ...
              (gRPC)        (gRPC)              (gRPC)       (gRPC)
```

- **鉴权能力收口在 gateway 内部**（无 auth-service）：登录/注册/发证/刷 token/登出/黑名单 都是 gateway 自己的 REST 接口 + 自己持久化，不再 RPC 出去。下游业务服务从 gRPC Metadata 拿 `x-user-id`，不感知 token。
- 腾讯云 IM 长连由 App 直连，**gateway 不维护 WS / SSE**（红线 7）。gateway 仅在登录时向 `im-service` 请求 UserSig 下发给 App。
- 大文件走对象存储 presigned PUT 直传，gateway 不读文件流。

## 3. 技术选型

| 类别 | 选型 | 备注 |
|---|---|---|
| 语言 / 运行时 | JDK 21 + `temurin:21-jre-alpine` | 与 `example-service` 一致 |
| Web 框架 | Spring Boot 3.3.5 + **Spring MVC + 虚拟线程**（`spring.threads.virtual.enabled=true`） | 同步编程模型 + 每请求一个虚拟线程，IO 阻塞自动 unmount，承载几万并发 |
| 持久层 | PostgreSQL + MyBatis-Plus + Flyway | 仅供鉴权域使用（设备指纹 / refresh token）；表前缀 `auth_` |
| gRPC 客户端 | `grpc-java` + `grpc-spring-boot-starter` | stub 从 Nexus 拉，例：`com.jianjiange.proto:user-proto:1.0.0` |
| 服务发现 | Nacos Discovery（net.devh + `spring-cloud-starter-alibaba-nacos-discovery` 提供 NameResolver） | gRPC 用 `discovery:///user-service` 寻址，不写死 host:port；本仓库首次落地 Nacos 跨服务 gRPC 客户端 |
| 鉴权 | JWT (`jjwt` 0.12.x) + Redis 黑名单 | **RS256 非对称**，私钥本地持有（仅 gateway 节点），公钥同样本地加载（用于验签） |
| 限流熔断 | Resilience4j | 接口级 / 用户级 / IP 级三档 |
| 参数校验 | `spring-boot-starter-validation` (JSR-380) | `@Valid` + `@RestControllerAdvice` |
| 对象映射 | **MapStruct（仅 proto→VO / entity） + 手写 proto builder** | 响应 proto→VO（`UserProfileConverter`）、DTO↔entity 用 MapStruct；请求侧 proto message 手写 builder（`ProtoReqBuilder`，MapStruct 不能 target protobuf builder，踩红线 6）；禁止 BeanUtils |
| 链路追踪 | Micrometer Tracing + W3C TraceContext | gRPC `ClientInterceptor` 透传 traceId |
| API 文档 | SpringDoc OpenAPI 2.x | 给前端 |
| 配置 | Nacos Config | JWT 公私钥句柄、限流阈值、下游服务名 |
| 缓存 | Redis（单实例，前缀 `gateway:`） | JWT 黑名单、refresh token 索引、限流计数；**不缓存下游业务数据** |

**为什么 MVC + 虚拟线程而不是 WebFlux**：

- JDK 21 已 GA Project Loom；Spring Boot 3.2+ 一行配置 `spring.threads.virtual.enabled=true` 即可让 Tomcat / `@Async` / `RestTemplate` / JDBC 调用全部跑在虚拟线程上。
- BFF 网关的瓶颈是 **IO 等待**（等下游 gRPC、等 PG、等 Redis），不是 CPU。传统平台线程每条 ~512KB 栈，几百并发就吃几百 MB；虚拟线程栈 KB 级，几万并发只多几十 MB，且阻塞时自动从载体线程上摘下。
- 保留 MVC 编程模型 = 调试栈线性、Validation / Actuator / SpringDoc / MapStruct / MDC 全部零改动复用，团队上手成本 0。
- WebFlux 的收益（更少线程数）已被虚拟线程吃掉大部分，但 Reactor 全栈响应式带来的调试难度、生态差异、人手成本是实打实的，**不划算**。

> 关键约束：BFF 并发执行用 `Executors.newVirtualThreadPerTaskExecutor()`，**不要把虚拟线程喂给传统固定线程池**（否则失去 unmount 能力）。同步阻塞代码遇到 `synchronized` 长块会 pin 载体线程，鉴权/聚合路径上的同步块要审一遍，必要时换 `ReentrantLock`。

## 4. 包结构

遵循 `CLAUDE.md` 的服务内部分层约定：

```
com.dating.mobilegateway
├── controller/      # @RestController；只做参数校验 + 调 service
├── service/         # 业务编排（BFF 聚合 / 鉴权流程）；调 manager + 多个 client
│   └── impl/
├── manager/         # 鉴权域单表/单聚合的数据访问编排
├── mapper/          # MyBatis-Plus Mapper（仅鉴权域：auth_device / auth_refresh_token）
├── entity/          # 鉴权域实体（与表 1:1）
├── client/          # gRPC stub 封装（一个下游一个 client 类）
│   ├── UserInfoClient.java
│   ├── RelationClient.java
│   └── ImClient.java
├── dto/             # 移动端入参（XxxReq）
├── vo/              # 移动端出参（XxxVO）
├── converter/       # MapStruct DTO ↔ proto message / DTO ↔ entity
├── filter/          # Servlet Filter：鉴权、限流、CORS、traceId
├── interceptor/     # gRPC ClientInterceptor：metadata 透传 userId/traceId
├── security/        # JWT 签发 / 验签 / 黑名单封装
├── config/
├── constant/
└── exception/       # 业务异常 + 全局异常处理
```

**调用方向**严格单向：`controller → service → (manager | client) → (mapper | gRPC stub)`。

- 禁止 controller 直接调 gRPC stub —— stub 必须包到 `client/`。
- 禁止 controller / service 直接调 mapper —— 走 `manager/`。
- gateway **持久层仅覆盖鉴权域**（`auth_*` 表前缀），不允许在 gateway 内放业务表 —— 业务数据一律 gRPC 调下游。

## 5. 关键设计

### 5.1 鉴权（并入网关，无独立 auth-service）

**已决策：鉴权能力全部内聚在 `mobile-gateway`**，不单独建 auth-service。理由：

- 当前阶段移动端是唯一接入端，鉴权流量与网关 1:1，独立服务只多一跳 gRPC 没有收益；
- JWT 验签每个请求都要做，签发/刷 token 跟验签同源（共用密钥、共用黑名单 / refresh 表），拆成两边维护成本高；
- 真要接入 H5 / web-bff 时，鉴权逻辑通过 `gateway-auth` 内部模块抽出来共享（同代码、不同进程），而不是上来就做远程服务化。

**支持的登录方式**（仅这三种，无密码、无邮箱）：

| 登录方式 | gateway REST | 依赖的 user-service RPC |
|---|---|---|
| 手机验证码 | `POST /api/v1/auth/login-phone` | `ResolveOrCreateByPhone` |
| 三方授权（Google / Apple / Facebook 等） | `POST /api/v1/auth/login-third-party` | `ResolveOrCreateByThirdParty` |
| 快速登录（设备 ID） | `POST /api/v1/auth/login-device` | `ResolveOrCreateByDevice` |

**承载的能力**（gateway 内部模块 `security/` + 鉴权域 `auth_*` 表）：

| 能力 | 落地 |
|---|---|
| 短信验证码下发 / 校验 | gateway REST 接口；验证码存 Redis；通过后调 user-service `ResolveOrCreateByPhone` |
| 三方 token 校验 | gateway 调对应第三方校验接口；通过后调 user-service `ResolveOrCreateByThirdParty` |
| 快速登录（设备 ID） | gateway 直接调 user-service `ResolveOrCreateByDevice`；可选叠加 DeviceCheck / Play Integrity |
| JWT 签发（access + refresh） | `security/JwtIssuer`；RS256 私钥从 Nacos Config 注入到内存，不落盘 |
| JWT 验签（请求路上） | `security/JwtAuthFilter`，本地公钥验签，**不走 RPC** |
| Refresh token | Opaque 字符串，hash 后存 PG `auth_refresh_token`，附带 deviceId / expiredAt |
| 强制登出 | Redis Set `gateway:auth:blacklist:<jti>`，TTL 与 token 同步 |
| 设备指纹 | PG `auth_device`，登录 / 异常告警时使用 |
| 与下游服务通信 | `GrpcClientMetadataInterceptor` 把解析后的 `x-user-id` / `x-device-id` / `x-trace-id` 通过 gRPC Metadata 透传，**下游不持有 token**；`app_name` 与目标 `user_id` 走 proto 请求体，不进 metadata |

**鉴权请求流**：

```
[App] --(Authorization: Bearer JWT)--> [gateway]
                                          │ 1. JwtAuthFilter 本地 RS256 验签（公钥内存命中）
                                          │ 2. 查 Redis 黑名单 gateway:auth:blacklist:<jti>
                                          │ 3. 解析 userId / deviceId 注入 RequestContextHolder
                                          ▼
                                     [gRPC ClientInterceptor]
                                          │ Metadata: x-user-id, x-device-id, x-trace-id
                                          ▼
                                     [下游业务 gRPC 服务（无需感知 token）]
```

**登录 / 刷新流**（以手机验证码登录为例，三方 / 设备登录结构相同，只换调用的 user-service RPC）：

```
[App] --POST /api/v1/auth/login-phone {phone, smsCode, deviceId, platform}--> [gateway]
                                                          │ AuthService.loginByPhone()
                                                          │   ├─ Redis 校验短信验证码
                                                          │   ├─ user-service.ResolveOrCreateByPhone(phoneE164, appName) → userId
                                                          │   ├─ user-service.CheckBan(userId)         // 命中则 401
                                                          │   ├─ AuthDeviceManager.upsert(userId, deviceId, ...)
                                                          │   ├─ JwtIssuer 签发 access(15min) + refresh(7d)
                                                          │   ├─ AuthRefreshTokenManager 落 PG（hash）
                                                          │   └─ ImClient.getUserSig(userId)        // 顺带拿 IM 票据
                                                          ▼
                              {access_token, refresh_token, user_sig, expire_in, userId, pending}

[App] --POST /api/v1/auth/refresh {refresh_token}-->  [gateway]
                                                          │ 1. 哈希 refresh_token 查 PG
                                                          │ 2. 校验未过期、未撤销、deviceId 匹配
                                                          │ 3. 旧 refresh 标记为已用，签发新对 access+refresh（轮换）
                                                          ▼
                                                 {access_token, refresh_token}
```

**密钥与黑名单**：

- RS256 公私钥都由 Nacos Config 注入，私钥内容 base64 编码，启动时加载到内存；轮换密钥时 Nacos 推送 → 应用监听 → 原子替换。
- 黑名单 key：`gateway:auth:blacklist:<jti>`，TTL = JWT 剩余有效期。强制登出 / 风控触发 写黑名单 + 撤销该用户全部 refresh token。

### 5.2 协议转换

**三段式**，禁止把 proto message 当 VO 直接返前端（`oneof` / `bytes` / 字段命名风格对前端不友好）：

```
controller(XxxReq)
    → converter(XxxReq → proto.XxxRequest)
        → client.gRPC(proto.XxxRequest → proto.XxxResponse)
    ← converter(proto.XxxResponse → XxxVO)
controller(XxxVO)
```

转换统一写在 `converter/` 包，一个领域一个 Converter：**响应侧** proto→VO 用 MapStruct（`UserProfileConverter`，读 getter）；**请求侧** VO/DTO→proto request 一律手写 builder（`ProtoReqBuilder`），因为 MapStruct 不能 target protobuf builder，引第三方扩展会踩红线 6。

### 5.3 BFF 聚合

**已决策：一开始就做 BFF 聚合**，按场景组装 VO。

```java
// 移动端"首页用户卡片" = 用户资料 + 关注关系 + IM 在线状态
@Service
public class HomeService {
    private final UserInfoClient userInfoClient;
    private final RelationClient relationClient;
    private final ImClient imClient;
    private final Executor bffExecutor;   // 专用线程池，不复用 Tomcat 线程

    public HomeCardVO getHomeCard(Long viewerId, Long targetId) {
        var p = CompletableFuture.supplyAsync(() -> userInfoClient.getProfile(targetId), bffExecutor);
        var r = CompletableFuture.supplyAsync(() -> relationClient.getRelation(viewerId, targetId), bffExecutor);
        var o = CompletableFuture.supplyAsync(() -> imClient.getOnline(targetId), bffExecutor);
        CompletableFuture.allOf(p, r, o).join();
        return HomeCardConverter.assemble(p.join(), r.join(), o.join());
    }
}
```

**注意**：

- BFF 编排专用线程池，跟 Tomcat 主线程池隔离，避免雪崩。
- 单接口总超时建议 800ms（Nacos 可配），单 RPC 子调用超时 500ms。
- 关键 RPC 失败 → 业务异常；可降级 RPC 失败 → 兜底默认值 + 打 WARN 日志。

### 5.4 限流

| 维度 | 实现 | 备注 |
|---|---|---|
| 接口级 | Resilience4j `@RateLimiter` | 配置在 Nacos，动态生效 |
| 用户级 | Redis + Lua 滑动窗口 | key `lock:gateway:rate:<userId>:<api>` |
| IP 级 | Nginx 层 | 不在 gateway 重复 |

限流命中返回 `Result{code=429}`（`TOO_MANY_REQUESTS`）。

### 5.5 文件 / 媒体上传

**不让 gateway 转发大文件**：

1. App 调 `POST /api/v1/upload/presign?bucket=avatar&ext=jpg` → gateway 调对应业务服务（如 `media-service`） → 返回对象存储 presigned PUT URL + object key。
2. App 直传对象存储（iDrive® e2 endpoint，dev/test/prod 按 bucket 隔离）。
3. App 上传完调 `POST /api/v1/upload/confirm` → 业务服务校验并落库 object key。

gateway 仅做轻量代理（presign / confirm），不读文件流，不持有对象存储凭证（凭证在业务服务，统一经 `dating-common` 的 `ObjectStorage` 接口访问）。

### 5.6 错误约定

- 统一 `Result<T>{code, message, data}` 包装；HTTP status 默认 200。
- 例外：
  - **401**：未登录 / token 失效 / 黑名单命中
  - **429**：限流命中
  - **500**：未捕获系统异常（返回 traceId，不暴露堆栈）
- gRPC `StatusRuntimeException` 由 `client/` 层捕获 → 转 `BizException` → 全局 `@RestControllerAdvice` → `Result`。业务代码不直接接触 gRPC 异常类型。
- **业务码分区**（与 user-service 对齐）：mobile-gateway 独占 `10500+`，user-service 独占 `10001–10499`，互不重叠，看码即知归属哪个服务。
  - `105xx` Token（TOKEN_INVALID=10501 / TOKEN_EXPIRED=10502 / TOKEN_REVOKED=10503 / REFRESH_TOKEN_REUSED=10504 / REFRESH_TOKEN_DEVICE_MISMATCH=10505）
  - `106xx` 短信 / 三方（SMS_CODE_INVALID=10601 / SMS_CODE_EXPIRED=10602 / THIRD_PARTY_TOKEN_INVALID=10603）
  - `109xx` 上游（UPSTREAM_UNAVAILABLE=10901）
- 码值定义以 `exception/ErrorCodes.java` 为准，本表只钉段位约定。

### 5.7 链路追踪

- HTTP 入口 Filter：生成 / 透传 `traceparent`（W3C TraceContext）。
- 注入 SLF4J MDC：`traceId`、`userId`。
- gRPC `ClientInterceptor` 把 `x-trace-id` 塞进 Metadata 透传到下游。
- 日志走 stdout，Loki 按 traceId 聚合。

### 5.8 版本策略（待最终拍板，倾向 URL）

- URL 路径带版本：`/api/v1/...`、`/api/v2/...`
- 路由直观、缓存友好、网关 / Nginx 都能按前缀分流。
- gRPC 端的版本通过 proto 包名管理（`user.v1` / `user.v2`），跟 REST 版本解耦。

## 6. 与现有约束的对齐

| CLAUDE.md 红线 | 本方案如何遵守 |
|---|---|
| 红线 1：持久层多表 JOIN | gateway 持久层仅鉴权域 2 张表（`auth_device` / `auth_refresh_token`），单表 CRUD 走 BaseMapper，组装在 service 层做 |
| 红线 2：跨服务直连别人库 | gateway 只持有自己的鉴权库表 + Redis `gateway:*` key；业务数据一律 gRPC 调下游 |
| 红线 3：服务间 HTTP 互调 | gateway → 下游全部 gRPC，gateway 自身才暴露 REST |
| 红线 4：proto 拷贝 / submodule | 三语言 stub 走 Nexus，gateway 仅声明 Maven 依赖 |
| 红线 5：密钥入仓 | JWT 公私钥、Redis / Nacos / DB 凭证全部走 Nacos Config + 环境变量 |
| 红线 6：未评审中间件 | 仅使用 Spring Boot / PG / Redis / Nacos / gRPC，全部在白名单内 |
| 红线 7：自建 IM / 长连 | gateway 不维护 WS / SSE；IM 走腾讯云 SDK，gateway 仅颁发 UserSig |

## 7. 不做什么（边界）

- ❌ 不做业务数据缓存中间层（各下游服务自管缓存）。
- ❌ 不直连任何**业务**库 / 业务 Redis key —— 鉴权域 `auth_*` 表是 gateway 自己的，不算跨服务。
- ❌ 不持有腾讯云 IM AppKey（统一在 `im-service`）。
- ❌ 不维护 WebSocket / SSE 长连。
- ❌ 不写业务逻辑 —— 只做：鉴权 + 协议转换 + 限流 + BFF 聚合 + 字段裁剪。
- ❌ 不接入第三方支付 / 推送等 SDK —— 这些归属各自垂直服务。
- ❌ 不单独起 auth-service —— 当前阶段没收益，将来如需多端复用，先抽 `gateway-auth` 内部模块，再视情况服务化。

## 8. 部署

- Dockerfile 复用 `example-service` 模板（builder `maven:3.9-temurin-21` → runtime `temurin:21-jre-alpine`）。
- 加入共享 Docker 网络 `dev-ops_my-network`。
- 容器暴露 HTTP `8080`；HTTPS 在外层 Nginx 终结。
- Nacos / Redis / JWT 公钥配置项从 Nacos Config 拉，凭证从环境变量注入，**不入仓**。
- 健康检查：Spring Boot Actuator `/actuator/health`，docker compose `healthcheck` 接入。
- 优雅停机：`server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s`，配合 Nacos 下线先摘流量再退出。

## 9. 后续待决策

- [ ] **接口签名 / 防重放**：敏感写接口是否引入 `timestamp + nonce + sign`？移动端是否做 SSL pinning？
- [ ] **灰度发布**：是否按 `userId` / `deviceId` 路由到下游不同版本？短期不做，gRPC client 留好 `ClientInterceptor` 钩子。
- [ ] **多端复用**：H5 / 第三方接入时，是同进程加 `/h5/` 前缀，还是另起 `web-bff`？倾向另起；届时鉴权抽 `gateway-auth` 内部模块共享。
- [ ] **JWT 算法**：默认 RS256（公私钥都在 gateway）。如未来抽 `gateway-auth` 模块给多端共享，再评估是否需要把签发能力收口到单点。

## 10. 落地清单（开工后）

1. 在 `proto` 仓库梳理 metadata 约定（`x-user-id` / `x-device-id` / `x-trace-id`）；**不**新增 `auth.v1` proto（鉴权不出 gateway）。
2. 新建 `mobile-gateway` 骨架：
   - pom / Dockerfile / 分层包（含 `manager` / `mapper` / `entity` / `security`）
   - `application.yml` 开 `spring.threads.virtual.enabled=true`
   - Nacos Config / Discovery 接入
   - Flyway 初始化鉴权两表（`auth_device` / `auth_refresh_token`）
   - `JwtAuthFilter` + `JwtIssuer` + Redis 黑名单
   - gRPC client 模板 + `ClientInterceptor`（透传 metadata）
   - Resilience4j + 全局异常 + SpringDoc
3. Nacos 配置：JWT 公私钥（base64）、限流阈值、各下游服务名映射、超时配置。
4. Nginx 配置：HTTPS 终结 + `/api/` 前缀转发到 `mobile-gateway:8080`。
5. Jenkins Pipeline 接入 `mobile-gateway` 构建（`Jenkinsfile` choices 加入新服务名）。
