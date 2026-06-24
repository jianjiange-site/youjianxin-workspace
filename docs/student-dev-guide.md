# Vibe 后端开发规范（学员版）

> **适用对象**：参与 dating-server 后端开发的学员。
> **覆盖范围**：从拿到机器、配 Git，到写第一行代码、提交、部署上线的全链路约定。

---

## 目录

- [0. 30 秒速览：你必须先记住的几条](#0-30-秒速览你必须先记住的几条)
- [1. 总体架构与微服务划分](#1-总体架构与微服务划分)
- [2. 技术栈强约束](#2-技术栈强约束)
- [3. Git 工作流与提交规范](#3-git-工作流与提交规范)
- [4. 接入共享开发环境](#4-接入共享开发环境)
- [5. 服务内部分层与目录约定](#5-服务内部分层与目录约定)
- [6. 各技术组件使用规范](#6-各技术组件使用规范)
- [7. 代码开发规范](#7-代码开发规范)
- [8. 测试与本地构建](#8-测试与本地构建)
- [9. 提交 → 部署 → 排障](#9-提交--部署--排障)
- [10. 红线汇总（违反一票否决）](#10-红线汇总违反一票否决)
- [附录 A：example-service 骨架解读](#附录-aexample-service-骨架解读)
- [附录 B：推荐工具链](#附录-b推荐工具链)

---

## 0. 30 秒速览：你必须先记住的几条

| # | 条目 |
|---|---|
| 1 | **生产密码 / AK SK / Token 一律不进 git**。**学员共享 dev 凭据**(`38.76.188.242`)可写入 workspace `nacos/<service>-<env>.yaml` 模板 + `docs/dev-onboarding.md`(方便复制粘贴到 Nacos);业务代码 / `application*.yml` / `.env*` 仍走 `${VAR}` 占位。|
| 2 | **持久层禁多表 JOIN**，一张 Mapper 只查一张表，跨表在 service 层多次单表查 + 内存拼装。|
| 3 | **服务间禁 HTTP 互调**，只能 gRPC；要对外（App / H5 / 第三方）才走 REST。|
| 4 | **跨服务禁直连别人家的库 / Redis key / 对象桶**，要数据就调对方 gRPC。|
| 5 | **时间一律 UTC**。DB 列 `TIMESTAMPTZ`、JVM `TZ=UTC`、连接 session `SET TIME ZONE 'UTC'`，本地时区只在 App 展示层换算。|
| 6 | **`.proto` 走 Nexus 包**：proto 文件放 workspace 根的 `proto/`，发布到 `nexus.jianjiange.site` 时**包坐标必须带你的拼音前缀**（`com.dating.<name>.proto:*` / `dating-proto-<name>-*`），业务工程通过 `<dependency>` 拉，不直接读 proto 文件路径。详见 §6.5 + [`dev-onboarding.md §8.1`](./dev-onboarding.md)。|
| 7 | **本机自己起 Jenkins + Loki/Grafana**：build / 部署 / 看日志全部在你本机的 docker 网络里跑，不用共享 `jenkins.jianjiange.site` 或 `logs.jianjiange.site`（那是团队基建）。|
| 8 | **业务服务不直连 OpenIM / LiveKit**，IM 能力（消息、Token、音视频）全部经 `im-service` gRPC 编排。|
| 9 | **代码 commit 完 push 到 workspace 仓库**，去你本机的 Jenkins UI **手动**点 Build 才会构建 + 部署 —— 不靠 push 自动跑。|

---

## 1. 总体架构与微服务划分

### 1.1 仓库组成

| 仓库 | 语言 | 职责 |
|---|---|---|
| **dating-server** | Java 21 / Spring Boot 3.3.5 | 7 个业务微服务的 monorepo（一服务一根目录） |
| **ai-chat** | Python ≥3.13 / LangChain / LangGraph | 数字人对话（ChatAgent）+ 图像理解（VisionAgent），gRPC 暴露 |
| **open-im** | docker-compose + 配置 | 自建 OpenIM Server v3.8.3 + LiveKit v1.7（文字 + 1v1 音视频） |
| **proto** | `.proto` + 三语言 stub | gRPC 接口契约的唯一 source of truth，经 Nexus 发布 |

> 本规范聚焦 **dating-server**。Python 服务（ai-chat）和 OpenIM 引擎本身只在「能力使用」维度提及，部署细节不归学员管。

### 1.2 现有微服务清单

```
dating-server/
├── mobile-gateway/    # 对外 BFF：REST → 内部 gRPC，鉴权 / 限流 / JWT
├── user-service/      # 用户档案、设备识别、推荐召回
├── im-service/        # 编排 OpenIM + LiveKit 的能力中枢
├── match-service/     # 匹配、推荐、超喜欢、配额
├── post-service/      # 朋友圈、Feed、评论、点赞
├── payment-service/   # 金币、订阅、PayPal Webhook
└── example-service/   # 新服务复制粘贴用的骨架
```

每个服务都是一个**独立 Maven 模块 + 独立镜像 + 独立部署**。仓库根目录**没有** parent pom——服务之间不共享父构建。

### 1.3 服务划分原则

- **按业务领域聚合根划分**：用户、消息、匹配、内容、支付各自独立，不交叉持有对方数据库。
- **mobile-gateway = BFF**：所有 App 请求的唯一公网入口，只做参数校验、JWT 鉴权、路由到内部 gRPC，**不做业务**。
- **im-service = 能力封装**：把 OpenIM / LiveKit 包成业务能扛的语义（"给 X 用户发系统消息"、"给 X 签一个通话 Token"）；任何业务想动 IM 都走它。
- **service-to-service 单向调用**：调用关系记录在 [ARCHITECTURE.md §2.3 依赖矩阵](./ARCHITECTURE.md)，**不允许成环**。
- **新增服务前先问**：「能不能拆成现有服务里多一个 gRPC 方法解决？」能就不开新服务。

---

## 2. 技术栈强约束

新服务一律照搬，引入清单**外**的中间件（ES / Mongo / ZK 等）前必须在 PR 里说明动机并获得 owner 认可（RocketMQ 已纳入清单,直接用）。

| 类别 | 选型 | 关键约束 |
|---|---|---|
| 语言 / 运行时 | **JDK 21** | 容器 `eclipse-temurin:21-jre-alpine` |
| Web | **Spring Boot 3.3.5** | parent POM `spring-boot-starter-parent` |
| ORM | **MyBatis-Plus** | 单表 CRUD 用 BaseMapper / LambdaQueryWrapper；复杂单表 SQL 走 XML；**禁多表 JOIN** |
| DB | **PostgreSQL 16** | 唯一持久化关系库，**禁 MySQL**、不写存储过程 / 触发器 |
| 缓存 | **Redis 7 单实例** | key 必须按服务前缀隔离，必带 TTL |
| 对象存储 | **MinIO（S3 兼容，自部署）** | 部署在 38 教学机；统一经 `dating-common` 的 `ObjectStorage` 接口（底层 AWS SDK v2 + `forcePathStyle`） |
| 配置 / 注册中心 | **Nacos 2.4** | Config + Discovery 同 namespace；gRPC 走 Nacos 服务发现 |
| RPC | **gRPC 1.68.1 + Protobuf 4.28.3** | **服务间禁 HTTP 互调**；`.proto` 文件在 workspace 根的 `proto/`，**打包发到共享 Nexus**（`com.dating.<name>.proto:*` / `dating-proto-<name>-*`），业务工程通过 `<dependency>` / `pip install` 拉 |
| 内部包仓库 | **Sonatype Nexus 3** | `nexus.jianjiange.site`，三协议（Maven / npm / PyPI） |
| IM / 音视频 | **OpenIM + LiveKit** | 业务服务统一经 `im-service` gRPC，**禁自建 WebSocket 长连** |
| MQ | **RocketMQ 5.3.1** | 部署在 38 教学机,已纳入基础组件清单,可直接用 |

---

## 3. Git 工作流与提交规范

### 3.0 Day 1：加入组织 + 建你自己的 workspace 仓库

学员的所有代码（Java 服务 / Python 服务 / proto 文件）**统一放在一个 workspace 仓库**里，组织在 GitHub 上叫 **`jianjiange-site`**：https://github.com/jianjiange-site

#### 步骤

1. **找管理员把你加进组织**：发你的 GitHub 用户名给管理员，等收到组织邀请邮件 → 接受。
2. **在组织下建你自己的仓库**：仓库名 **`<yourpinyin>-workspace`**（拼音全小写，无空格）。
   - 例：`alice-workspace`、`zhangsan-workspace`、`liming-workspace`
   - 私有 / 公开都行，建议私有
   - **不要勾 README / .gitignore / license**，等下我们手动建
3. **本机 clone + 建三个顶层目录**：
   ```bash
   git clone git@github.com:jianjiange-site/<yourpinyin>-workspace.git
   cd <yourpinyin>-workspace
   mkdir -p ai-chat dating-server proto
   touch ai-chat/.gitkeep dating-server/.gitkeep proto/.gitkeep
   git add . && git commit -m "chore: bootstrap workspace skeleton"
   git push origin main
   ```

#### 目录约定（强制）

```
<yourpinyin>-workspace/
├── ai-chat/           # Python 服务（数字人对话 / Vision Agent，将来对接 dating-server）
├── dating-server/     # Java 微服务 monorepo
│   ├── mobile-gateway/
│   ├── user-service/
│   ├── im-service/
│   ├── match-service/
│   ├── post-service/
│   ├── payment-service/
│   └── example-service/
└── proto/             # 所有 .proto 文件统一放这里，Java / Python 共享
    ├── user/
    │   ├── user_profile.proto
    │   └── user_identity.proto
    ├── match/
    ├── im/
    └── common/
        └── result.proto
```

这套结构对应团队 monorepo `dating-workspace` 的三个独立仓库（`ai-chat` / `dating-server` / `proto`），学员阶段合一个 repo 写就行；将来切到团队模式时把三个目录分别推到独立仓库即可，代码不用改。

> ⚠️ 一旦你建好了 workspace 仓库，**所有改动都在这个仓库里 commit / push**，不再到处建新 repo。

### 3.1 分支模型

```
master   ←  生产稳定线，受保护，只接合并 PR；打 tag 即代表一次发版。
dev      ←  集成主线，所有日常开发汇合在这里；Jenkins dev 流水线源
<your>/<topic>   ←  个人特性分支，名字带你自己 + 一句概括
```

| 分支 | 直接 push？ | 谁可以合并 | 备注 |
|---|---|---|---|
| `master` | ❌ | 只接受 `dev` → `master` 的 PR（发版才合） | 推 master 前必须先推 dev |
| `dev` | ❌ 推荐走 PR | 个人分支 → dev 走 PR | Jenkins 部署源 |
| `<your>/<topic>` | ✅ 你自己 | — | 例：`alice/match-quota-fix` |

### 3.2 命名约定

- 个人分支：`<你的英文名/花名>/<功能或问题简述>`，全小写、用 `-` 分词
  - ✅ `alice/match-quota-fix`、`bob/im-service-callback-refactor`
  - ❌ `feature/test`、`new-branch-1`
- 紧急修复：`hotfix/<simple-desc>`，从 `master` 切，修完同时合 `master` 和 `dev`
- 实验性 / 草稿：`spike/<topic>`，不进 dev

### 3.3 Commit Message（Conventional Commits）

格式：`<type>(<service>): <概要>`

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | bug 修复 |
| `refactor` | 不改外部行为的重构 |
| `perf` | 性能优化 |
| `docs` | 文档（不动代码） |
| `test` | 仅加测试 |
| `chore` | 构建脚本 / CI / 依赖升级 |

- ✅ `feat(im-service): 支持 LiveKit Token 短 TTL 重签`
- ✅ `fix(payment-service): 修复金币消费幂等 key 与并发的竞争`
- ❌ `update code`、`bug 修复`、`commit by alice`

> 单 commit 改太多就拆。一个 commit 一件事，rebase / cherry-pick 时不会拖累其他变更。

### 3.4 PR 流程

1. **本地构建必须通过**：`mvn -B -ntp clean package`（不通过禁止 push）。
2. **自测 + 单测**：写新功能至少补 service 层关键路径单测。
3. **PR 标题用 commit 风格**（同 §3.3）。
4. **PR 描述模板**：
   ```markdown
   ## 改动
   - <一句话总结改了啥>
   
   ## 背景 / 动机
   - <为什么改，关联的 issue / 设计文档>
   
   ## 自测清单
   - [ ] 本地 `mvn clean package` 通过
   - [ ] 关键路径单测覆盖
   - [ ] 跑通本服务 dev 部署，主流程功能正常
   - [ ] DB 变更：附 Flyway migration 文件 + 回滚说明
   - [ ] 接口变更：`.proto` 已更新 + `mvn clean package` 重新生成 stub 跑通；如果别的服务依赖这个 proto，跨服务一起重新构建
   ```
5. **Reviewer ≥ 1**，CI 全绿，合 `dev`。
6. **合完 → 立即手动触发 Jenkins dev 流水线**（不会自动跑，见 §9.1）。

### 3.5 .gitignore 必备

每个服务自己的 `.gitignore` 至少包含：

```gitignore
target/
*.iml
.idea/
.vscode/
.DS_Store
.env
.env.*
!.env.*.example
*.local.yml
application-local.yml
deploy/.env.deploy
```

### 3.6 红线

- 🚫 **真实密码 / AK SK / Token / RSA 私钥进 git**（不管在 `application.yml` 还是 `.env.dev`），一旦发现 PR 拒绝合并 + 必须**立刻 rotate** 暴露的凭据。
- 🚫 `git push --force` 到 `master` / `dev`。
- 🚫 commit 二进制大文件 / 日志 / 本地数据（用 `.gitignore` 拦住）。
- 🚫 跳过 pre-commit hook（`--no-verify`）。hook 报错就修，不要绕过。

---

## 4. 接入共享开发环境

> 不要在本机装一整套中间件 —— 团队已经把 PG / Redis / Nacos / MinIO / RocketMQ / OpenIM 部好在 `38.76.188.242`，本机服务直连即可。完整速查 + 各端示例配置见 [`dev-onboarding.md`](./dev-onboarding.md)。

### 4.1 速查表（连远端共享 dev）

| 组件 | 连接地址 | Web 控制台 | 账号 / 密码 |
|---|---|---|---|
| PostgreSQL | `38.76.188.242:5433` | DataGrip | `jianjian_test` / `MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V` |
| Redis | `38.76.188.242:6380` | DataGrip | （无用户名）/ `sNuP9gZScsj88QbEyTujffOvRCCH9Kv1` |
| MinIO（S3 兼容） | `https://minio-api.jianjiange.site` | https://minio.jianjiange.site | `admin` / `GorLDkuOhGyK5c1RXh2gaPooXgtso/MR` |
| Nacos | `38.76.188.242:8848` | http://38.76.188.242:8848/nacos | `nacos` / `jianjiange` |
| RocketMQ | NameServer `38.76.188.242:9876` | https://rocketmq.jianjiange.site | AK `rocketmq-student` / SK `5cafa390b8a42c25`（dashboard：`student` / `MwTt14eUL9s1M3`） |
| OpenIM | `https://nexus-mind.chatvibe.me/api` + `wss://nexus-mind.chatvibe.me/msg_gateway` | https://openim-admin.chatvibe.me | 找管理员要 |

> ⚠️ MinIO 服务器不对外开端口，本机代码必须走 `https://minio-api.jianjiange.site`，不是 `38.76.188.242:18900`。

### 4.2 多人隔离原则（共享环境必看）

| 资源 | 每个人自己用什么 |
|---|---|
| PG 数据库 | 自己建一个 `dating_dev_<yourname>` 库 |
| Redis db / key | 选一个空 db 号（dev 默认 db 1），**并且** 所有 key 前缀加你的名字：`<yourname>:<service>:<domain>:<id>` |
| Nacos namespace | 自己建 `dev-<yourname>` namespace |
| MinIO bucket | 自己建 `dating-<yourname>` bucket |
| RocketMQ topic / group | 一律加 `dev_<yourname>_` 前缀 |
| OpenIM userID | 自己用 `<name>_<yourname>` 前缀 |

**不允许覆盖别人的数据 / 配置 / topic。** 写代码前先看下别人有没有同名 key / topic。

### 4.3 本地 `application-dev.yml` 模板

完整版见 [`dev-onboarding.md §1`](./dev-onboarding.md)。要点：

```yaml
spring:
  application:
    name: dating-<your>-service
  datasource:
    url: jdbc:postgresql://38.76.188.242:5433/dating_dev_<yourname>?stringtype=unspecified
    username: jianjian_test
    password: ${PG_PASSWORD}                  # 不要直接写明文，从环境变量读
    hikari:
      connection-init-sql: SET TIME ZONE 'UTC'   # 强制 UTC
  data:
    redis:
      host: 38.76.188.242
      port: 6380
      password: ${REDIS_PASSWORD}
      database: 1
  cloud:
    nacos:
      discovery:
        server-addr: 38.76.188.242:8848
        namespace: dev-<yourname>             # 配置 + 注册必须同 namespace
        username: nacos
        password: ${NACOS_PASSWORD}
      config:
        server-addr: 38.76.188.242:8848
        namespace: dev-<yourname>
        username: nacos
        password: ${NACOS_PASSWORD}
        file-extension: yaml
```

启动时 IDE Run Configuration 加：
```
SPRING_PROFILES_ACTIVE=dev
PG_PASSWORD=…
REDIS_PASSWORD=…
NACOS_PASSWORD=jianjiange
```

> `application-dev.yml` 里**只放占位 `${VAR}`**，密码进 env 不进 git。

---

## 5. 服务内部分层与目录约定

每个服务的源码必须按下面结构组织：

```
com.dating.<service>
├── controller/    # HTTP 入口；只做参数校验 + 调 service；禁直接调 mapper
├── grpc/          # gRPC 服务端实现；同样只编排 service
├── service/       # 业务编排层；事务边界在这里；可调多个 manager / 远程 service
│   └── impl/
├── manager/       # 单聚合的数据访问编排；包装多次 mapper 调用 + 缓存读写
├── mapper/        # MyBatis-Plus Mapper 接口；一个 Mapper 只服务一张表
├── entity/        # 数据库实体；与表 1:1
├── dto/           # 入参 DTO（含 gRPC req 转换）
├── vo/            # 出参 VO
├── client/        # 调用其他服务的 gRPC stub 封装 / 第三方 SDK 包装
├── config/        # @Configuration
├── constant/      # 常量、枚举
└── exception/     # 业务异常 + 全局异常处理
```

**调用方向严格单向**：

```
controller / grpc  →  service  →  manager  →  mapper
```

禁止反向依赖、禁止跨层（如 controller 直接调 mapper、service 直接调别的服务的 mapper）。

### 反模式举例

| ❌ 写法 | ✅ 写法 |
|---|---|
| controller 里 `@Autowired UserMapper` | controller 调 `UserService`，service 内部用 `UserManager` 包 mapper |
| service A 直接 `@Autowired ServiceB`（同进程） | 如果是同服务，OK；**跨服务**必须经 gRPC `client` |
| Mapper XML 里写 `LEFT JOIN order` | 拆成两次单表查，在 service 拼装 |
| controller 抛 `throw new Exception("xxx")` | 抛 `BizException(code, message)`，全局异常处理转 Result |

---

## 6. 各技术组件使用规范

### 6.1 PostgreSQL

- **库 / 表命名**：snake_case；表名复数（`users` / `posts`）；外键无强约束（业务层保障一致性）。
- **必备列**：
  - `id`：内部主键，`bigserial` 自增即可，**仅用于物理存储 / 索引**，不直接对外暴露。
  - **业务主键**：每张表**必须**有一个跨库稳定的业务主键（雪花 ID / UUID / 业务编号），加唯一索引。命名两种选法都可以：
    - **通用风格**：统一叫 `biz_id`，写代码 / 改表都不用想
    - **语义风格（推荐）**：按表实体命名 —— `users.user_id`、`matches.match_id`、`orders.order_id`、`posts.post_id`
  - 业务主键的作用：**数据迁移 / 跨实例对账 / 跨服务引用 / 对外 API 出参**都用它；`id` 在新库会重排，业务主键不会。一旦写入不可变更。
  - `created_at`、`updated_at`：均 `TIMESTAMPTZ`
  - `deleted`：`@TableLogic` 逻辑删除标记
  - 反例：`SELECT * FROM users WHERE id = 123` 暴露给外部 —— 应该用 `user_id`；`id = 123` 在 dev 库和 prod 库可能指向完全不同的用户。
- **时间一律 UTC**：列用 `TIMESTAMPTZ`，连接 session `SET TIME ZONE 'UTC'`，**禁用 `TIMESTAMP`（无时区）**。
- **Mapper 一张表一个**：禁止 `LEFT JOIN` / `INNER JOIN` 跨表，跨表数据 service 层拼装；批量场景用 `in (...)` 一次性捞，避免 N+1。
- **跨服务取数据走 gRPC**，不直连别人家的库（红线）。
- **Flyway**：每个服务有自己的 `flyway_history_<service>` 表，migration 文件命名 `V<yyyyMMdd>_<seq>__<desc>.sql`，**历史 migration 一旦合并不可改**，要改只能加新 migration。
- **慢查 SOP**：用 `EXPLAIN ANALYZE` 看执行计划，必要时建复合索引（列顺序：选择性高的在前）。

### 6.2 Redis

> 共享 dev Redis 是**所有学员共用一个实例**，必须做隔离，否则你写的 key 会被同名 key 覆盖、扫 key 时也会扫到别人的数据。

- **Key 命名（学员强制规范）**：`<yourname>:<service>:<domain>:<id>`
  - 第一段 **必须是你的英文名 / 花名**，全小写、不带空格
  - 后面三段保持团队约定（服务名 / 业务子域 / 主键）
  - 例：
    - ✅ `alice:user:profile:1024`
    - ✅ `bob:gateway:auth:sms:code:+8613800000001`
    - ❌ `user:profile:1024`（没前缀，会和别人撞）
    - ❌ `Alice:user:profile:1024`（大写、不一致）
- **应用层做法**：在 `application-dev.yml` 把名字配成一个变量，所有 `RedisTemplate` / Redisson 都用它拼 key，不要散落在各处硬编码：

  ```yaml
  app:
    cache:
      key-prefix: ${REDIS_KEY_PREFIX:alice}   # IDE Run Configuration 里设 REDIS_KEY_PREFIX=<yourname>
  ```

  ```java
  @Value("${app.cache.key-prefix}")
  private String prefix;
  
  String key = prefix + ":user:profile:" + userId;   // → alice:user:profile:1024
  ```

  这样换成 prod 环境时（统一团队前缀或空前缀）只改一行配置，业务代码不动。
- **看自己 key**：`redis-cli -h 38.76.188.242 -p 6380 -a '<pwd>' --scan --pattern 'alice:*'`
- **TTL 必填**：所有 key 显式 `expire`，禁止永久 key（白名单除外，需注释说明）。Dev 环境建议 TTL ≤ 1 天，避免你跑过的临时数据长期占内存。
- **缓存一致性**：只用 cache aside —— **「先写库，再删缓存」**，**禁双写**。删缓存失败时按业务容错（重试 / 后续懒加载）。
- **分布式锁**：统一 Redisson，key 前缀 `<yourname>:lock:<service>:...`，必须设 `leaseTime` 兜底。
- **多 DB 隔离**：dev 用 db 1，prod 用 db 0。OpenIM 自己用单独 Redis，与业务 Redis 物理隔离。
- **不要把 Redis 当数据库**：所有状态必须能从 PG 重建。
- **不要 `FLUSHDB` / `FLUSHALL`**：你以为只清自己的，实际把整个 db 全清了，其他学员的缓存也没了 —— 共享环境这是事故。要清自己的：`redis-cli ... --scan --pattern 'alice:*' | xargs redis-cli ... del`。

### 6.3 对象存储（MinIO / S3 兼容）

#### 连接信息（共享 dev）

| 项 | 值 |
|---|---|
| 服务端 endpoint（代码用） | `https://minio-api.jianjiange.site` |
| Web 控制台 | https://minio.jianjiange.site |
| AccessKey | `admin` |
| SecretKey | `GorLDkuOhGyK5c1RXh2gaPooXgtso/MR` |
| 路径风格 | **必须 `path-style-access: true`**（MinIO 强制） |

> ⚠️ 服务器**只对外开 `minio-api.jianjiange.site`**，**不要**用 `38.76.188.242:18900` —— 那个端口只绑 127.0.0.1，连不上。

#### `application-dev.yml` 接入

```yaml
dating:
  object-storage:
    provider: s3
    endpoint: https://minio-api.jianjiange.site
    region: us-east-1                 # MinIO 不校验，占位即可
    access-key: ${MINIO_ACCESS_KEY}   # 从环境变量读
    secret-key: ${MINIO_SECRET_KEY}
    path-style-access: true           # 必须 true，否则 SignatureDoesNotMatch
    bucket: dating-<yourname>         # 你自己建的桶，见下
```

#### 自己建一个桶

控制台 https://minio.jianjiange.site → 左侧 **Buckets** → **Create Bucket** → 名字填 `dating-<yourname>` → Create。

#### 桶 / Key 规范（团队约定，照做）

- **一服务一桶**：命名 `dating-<service>`（团队共享场景）/ `dating-<yourname>`（个人学习场景）。
- **服务内禁开第二个桶**：不同类型文件用 object key **目录前缀**区分（不是建多个桶）。
- **Object Key 强制格式**：

  ```
  <category>/<owner_id>/<yyyymm>/<uuid>.<ext>
  ```

  | 段 | 含义 | 例 |
  |---|---|---|
  | `<category>` | 文件类型 / 业务子域 | `avatar` / `post-image` / `attachment` / `tmp` |
  | `<owner_id>` | 数据归属 ID | user_id / post_id |
  | `<yyyymm>` | 写入年月分片，便于归档 | `202606` |
  | `<uuid>.<ext>` | 防碰撞 + 保留扩展名 | `c9f...d2.jpg` |

  - 临时上传一律 `tmp/` 前缀，桶配 24h Lifecycle Rule 自动清。
  - 入库**只存 object key**，不存完整 URL。

#### 出参契约

- **VO / gRPC 出参一律回 `*_key`**，App 拿到 key 自拼 `${cdnBaseUrl}/${bucket}/${key}`。
- **服务端不调 `publicUrl(...)` 拼 URL 返回**（CDN / endpoint / bucket 改了，App 改一个配置就行，不用逐服务改实现）。
- **例外**：敏感资产（身份证、私聊文件、付费内容）由后端按需签 `presignedGetUrl` 下发，TTL 短（分钟级）；此时 VO 字段可叫 `*_url`。

#### Presigned URL 适用范围（必看）

Presigned URL 是**临时授权机制**，**只用于无凭据的外部客户端**（App / H5 / 第三方）：

| 调用方 | 上行（写） | 下行（读） |
|---|---|---|
| App / H5 / 外部 | **Presigned PUT URL**（App 直传 MinIO） | 私有桶 + 敏感 → **Presigned GET URL**；公开资产 → CDN public + App 自拼 URL |
| 内部 RPC 服务（持 AK/SK） | `objectStorage.putObject(...)` 直写 | `objectStorage.getObject(...)` / `headObjectSize` / `doesObjectExist` 直读 |

**重点**：内部 RPC **不要**给自己签 URL 再自己消费 —— 把"对外授权"语义错配到"内部访问"，徒增错误面和审计噪音。

#### 跨服务引用对象

- **禁直接读写别人服务的桶**（权限放大 / 内部存储耦合 / 绕过业务校验）。
- **正确做法**：调对象所有方的 gRPC，由 owner service 决定如何返回：
  - 公开资产（头像、运营图）：owner 透传 `object_key`，调用方 / App 自拼 URL。
  - 内部消费 bytes（ML 流水线取照片做特征）：owner `getObject` 后透传 bytes。
  - 敏感资产 + 必须签 URL（身份证、私聊文件）：owner 调 `presignedGetUrl` 签好作为 gRPC response 字段透传。

  ```proto
  rpc BatchGetAvatars(BatchGetAvatarsRequest) returns (BatchGetAvatarsResponse);
  
  message AvatarKeys {
    string original_key = 1;
    string min_key      = 2;
    string mid_key      = 3;
  }
  ```

#### 学员侧常见坑

| 现象 | 原因 | 解决 |
|---|---|---|
| `SignatureDoesNotMatch` | 漏了 `path-style-access` | 配置加 `path-style-access: true` |
| 连 `38.76.188.242:18900` 不通 | 服务器没对外开该端口 | 改用 `https://minio-api.jianjiange.site` |
| 上传成功 App 拿不到 URL | 服务端拼了 URL 返回 | 改回回 `*_key`，让 App 自拼 |
| 别人能看到你的对象 | 桶共用且没用 owner_id 前缀 | object key 必须带 `<owner_id>`，service 层校验 caller 身份匹配 |

### 6.4 Nacos

- **namespace 隔离**：
  - dev：`dating_chat_dev`（团队共用） / `dev-<yourname>`（你个人调试）
  - prod：`dating_chat_prod`
  - **禁用 `public` namespace**。
- **Config 与 Discovery 必须同 namespace**，否则 `discovery:///user-service` 解析不到实例。
- **dataId 命名**：
  - `<service>.yaml`：所有 profile 共用
  - `<service>-<profile>.yaml`：profile 覆盖（如 `user-service-dev.yaml`）
  - `group` 统一 `DEFAULT_GROUP`，`file-extension` 统一 `yaml`（禁 properties）。
- **什么放 Nacos / 什么放 `.env.deploy`**：

  | 类别 | 放哪里 | 例子 |
  |---|---|---|
  | Nacos 自身连接 | `.env.deploy` | `NACOS_ADDR` `NACOS_USER` `NACOS_PASSWORD` `NACOS_NAMESPACE` |
  | 启动必需的基础设施坐标 | `.env.deploy` | `DB_HOST` `DB_PORT` `DB_PASSWORD` `REDIS_HOST` `REDIS_PASSWORD` |
  | 应用级凭据 / 密钥 | **Nacos** | 对象存储 AK SK、JWT 公私钥、第三方 SDK AppKey |
  | 业务可调参数（hot reload） | **Nacos** | 限流阈值、功能开关、短信渠道开关、TTL |
  | 结构性默认值 | `application.yml` | 端口、线程池、连接池上限、日志级别 |

- **真实凭据进 Nacos 不进仓**。

### 6.5 gRPC

#### proto 走 Nexus 包的方式（学员阶段也走这套，不直接读文件系统）

虽然 `.proto` 文件放在 workspace 根的 `proto/` 目录里编辑，但**业务工程不直接引用文件路径**。流程是：

```
你在 proto/ 编辑 .proto
   ↓ 在 proto/ 下跑 mvn deploy / twine upload
共享 Nexus（https://nexus.jianjiange.site）发布带你前缀的包
   ↓
你的服务 pom.xml 加 <dependency>com.dating.<name>.proto:<svc>-proto:0.1.0</dependency>
   ↓ mvn clean package
生成 stub，业务代码 import 用
```

#### 包坐标必须带你的名字前缀

多人共享一个 Nexus，**别和别人撞**：

| 语言 | 包坐标（把 `<name>` 换成你的拼音） |
|---|---|
| Java | `com.dating.<name>.proto:<service>-proto:0.1.0`（例 `com.dating.alice.proto:user-proto:0.1.0`） |
| Python | `dating-proto-<name>-<service>==0.1.0`（例 `dating-proto-alice-user==0.1.0`） |

发布仓库：
- Java：snapshot → `maven-snapshots`，release → `maven-releases`
- Python：→ `pypi-hosted`

#### 完整的发布 / 拉取步骤

写得太长会和 dev-onboarding 重复，**直接看这两节**：
- 发布到 Nexus：[`dev-onboarding.md §8.1`](./dev-onboarding.md)（pom.xml 的 `distributionManagement`、`~/.m2/settings.xml`、Python 的 `twine upload`）
- 业务工程引用：[`dev-onboarding.md §8.2`](./dev-onboarding.md)（pom 加 `nexus-public` 仓 + `<dependency>`；pip 配 `index-url`）

> Nexus 账号（`NEXUS_USER` / `NEXUS_PASS`）找管理员要，**绝不入仓**，写进 `~/.m2/settings.xml` 和 `~/.pip/pip.conf`（这些是用户级配置，不进 git）。

#### 改了 proto 必做三步

1. **升版本号**：同版本号在 Nexus 只能发一次。`0.1.0` → `0.1.1`（修复）/ `0.2.0`（加字段）/ `1.0.0`（破坏性改动）。
2. **重新 `mvn deploy` / `twine upload`** 把新版本推到 Nexus。
3. **所有依赖方 pom / pyproject.toml 跟着升版本**，然后 `mvn clean package` / `pip install -U` 拉新 stub。

> 这套和团队正式生产**完全一致**（生产侧只是去掉 `<name>` 前缀，三语言同步发版），学员现在练熟，将来切过去无成本。

#### 其他约定（无论哪种方式发布都必须遵守）

- **服务间禁 HTTP 互调**：只能 gRPC；要内部相互调用就走 stub。
- **版本号锁死**：禁 `LATEST` / `RELEASE` / 范围版本 / `^` / `~`，必须固定到 `0.1.0` 这种具体版本。
- **服务发现走 Nacos**：客户端用 `discovery:///<service-name>` 寻址，**禁写死 host:port**。
- **对外才暴露 REST**：REST Controller 与 gRPC Service 共享 service 层，不重复写业务。
- **错误码**：内部 gRPC 抛 `BizException` → 拦截器映射到 `Status.fromCodeValue(...)` + `withDescription(code|message)`；client 反向解出再转回 `BizException`。

### 6.6 RocketMQ（教学环境）

- 当前部署在 **38（教学机）**，dating-server 业务侧**不强依赖**；如要引入，PR 评审。
- **客户端必须带 AK / SK**（公网，没带连不上）：
  ```yaml
  rocketmq:
    name-server: 38.76.188.242:9876
    producer:
      group: dev_<yourname>_producer
      access-key: rocketmq-student
      secret-key: 5cafa390b8a42c25
  ```
- **topic / group 一律 `dev_<yourname>_` 前缀**，避免和别人撞。
- **Topic 先在 Dashboard 建**再发消息，避免依赖 `autoCreateTopicEnable`（生产场景一律关）。
- **broker commitlog 只保留 24h**，**禁把 MQ 当长期存储**。

### 6.7 IM（OpenIM + LiveKit）

- 所有 IM 能力（消息收发、好友、会话、LiveKit Token 签发）**统一经 `im-service` gRPC**，其他服务不直接调 OpenIM REST、不直接签 LiveKit Token、不直接持 OpenIM admin secret。
- 业务服务**禁自建 WebSocket 长连**（红线）。
- 客户端用 OpenIM SDK 直连 WS `wss://nexus-mind.chatvibe.me/msg_gateway`；音视频用 LiveKit SDK，地址 / Token 由 `im-service` 通过 OpenIM 自定义消息下发。
- IM 中间件（OpenIM 用的 MongoDB / Kafka / Etcd）**业务侧严禁直连**。

---

## 7. 代码开发规范

### 7.1 命名

| 元素 | 风格 | 例 |
|---|---|---|
| 类 | `UpperCamel` | `UserProfileService` |
| 方法 / 变量 | `lowerCamel` | `getUserProfile` |
| 常量 | `UPPER_SNAKE` | `MAX_RETRY_COUNT` |
| 包名 | 全小写无下划线 | `com.dating.user.service` |
| DTO 后缀 | `Req` / `Resp` / `DTO` | `LoginReq` / `LoginResp` |
| VO 后缀 | `VO` | `UserProfileVO` |

### 7.2 接口返回

REST 统一用 `Result<T>{ code, message, data }` 包装；HTTP 状态码用 200，业务错误用 code 区分。

```java
@GetMapping("/{userId}")
public Result<UserProfileVO> getProfile(@PathVariable Long userId) {
    return Result.ok(userProfileService.getProfile(userId));
}
```

### 7.3 异常

- 业务异常继承 `BizException(code, message)`：
  ```java
  public class UserNotFoundException extends BizException {
      public UserNotFoundException(Long userId) {
          super(ErrorCode.USER_NOT_FOUND, "用户不存在: " + userId);
      }
  }
  ```
- 全局 `@RestControllerAdvice` 兜底：
  - `BizException` → `Result.fail(code, message)`
  - 其他系统异常 → ERROR 日志（带堆栈）+ 返回通用 500 文案，**不把堆栈抛给前端**。

### 7.4 事务

- `@Transactional(rollbackFor = Exception.class)` 加在 service 方法上。
- **禁止跨服务事务**：远程调用不参与本地事务。要跨服务一致性用消息 / 重试 / 对账，**不要分布式事务**（XA / Seata 都不上）。

### 7.5 日志

- 用 **SLF4J**，禁止 `System.out.println`。
- 日志走 **stdout**，由你本机的 Promtail → Loki 采集，浏览器开 **http://localhost:3000** （本机 Grafana）查询。完整部署见 [`dev-onboarding.md §8.5`](./dev-onboarding.md)。
- ERROR 日志必带堆栈：`log.error("xxx failed, userId={}", userId, e)`。
- 关键链路打 `traceId` / `userId`（MDC 透传）。
- 不要打印**任何**敏感字段（密码、token、AK SK、身份证、手机号脱敏）。

### 7.6 时区

**全系统统一 UTC**：
- JVM：容器 `TZ=UTC` + `-Duser.timezone=UTC`
- DB：列 `TIMESTAMPTZ`，连接 `SET TIME ZONE 'UTC'`
- 业务主键：雪花 ID 的日期段也走 UTC
- 日志：时间戳 UTC
- **代码 / SQL 禁止写死 `Asia/Shanghai` 等本地时区**

本地时区只在展示层（App / H5）按用户所在时区即时转换。

### 7.7 配置

- 环境敏感值（DB / Redis / 对象存储 / OpenIM secret / LiveKit key 等）放 **Nacos**，绝不入仓。
- 本地默认值放 `application.yml`，profile 切换走 `SPRING_PROFILES_ACTIVE`。
- 真实密码 / 私钥 / token 一律 `${ENV_VAR}` 占位，不准明文。

### 7.8 空值

- 返回集合用**空集合**不要 null（`Collections.emptyList()`）。
- 返回对象允许 null，但调用方必须 `Optional` 或显式判空。
- 公共参数用 `@NotNull` / `@NotBlank` 校验，service 入口处统一 throw。

### 7.9 DTO ↔ Entity 转换

- 手写转换或用 **MapStruct**。
- **禁用 `BeanUtils.copyProperties` 在生产路径上做反射拷贝**（性能差 + 静默丢字段）。

### 7.10 注释（学员强制）

学员阶段以**写清楚**为第一优先，不省注释。下面两条是底线，review 时缺一条都退回。

#### 一、每个方法必须有 Javadoc

包含**功能描述 + 入参 + 出参 + 异常**，紧贴方法签名上方：

```java
/**
 * 根据 user_id 查询用户基础档案，命中缓存返回缓存值，未命中回源 DB 并回填缓存。
 *
 * @param userId 业务主键 user_id（不是内部 id），必填，> 0
 * @return 用户档案 VO；用户不存在 / 已逻辑删除时抛异常，不返回 null
 * @throws UserNotFoundException 用户不存在或已删除
 */
public UserProfileVO getProfile(Long userId) {
    ...
}
```

- **public / 对外方法**：必写完整 Javadoc。
- **private 辅助方法**：至少一行说明用途；入参 / 出参语义不显然的，也补 `@param` / `@return`。
- **gRPC 方法实现 / Controller**：尤其要写清楚 —— 这是别人接入的契约。
- **不写**「这个方法做了什么」式套话（"This method gets profile"），要写**为什么这么做、有什么前置条件、调用方需要注意什么**。

#### 二、函数内每一逻辑块用单行注释划清

哪怕不到 5 行也写一句。让人读代码时**先看注释串起流程**，再看具体实现：

```java
public Result<UserProfileVO> updateProfile(Long userId, UpdateProfileReq req) {
    // 1. 参数校验：昵称长度 / 头像 key 前缀必须是 caller 自己的 user_id
    validateNickname(req.getNickname());
    validateAvatarKeyOwnership(userId, req.getAvatarKey());

    // 2. 分布式锁：同一 user_id 串行化，避免并发改昵称导致缓存与 DB 不一致
    RLock lock = redissonClient.getLock("alice:lock:user:profile:" + userId);
    if (!lock.tryLock(0, 10, TimeUnit.SECONDS)) {
        throw new BizException(ErrorCode.RESOURCE_BUSY, "请稍后重试");
    }

    try {
        // 3. 落库：用户档案表 + 头像表都改，本地事务保证原子
        userManager.updateProfile(userId, req);
        avatarManager.refreshLatest(userId, req.getAvatarKey());

        // 4. cache aside —— 先写库再删缓存，删失败走重试队列
        cacheManager.evict("alice:user:profile:" + userId);

        // 5. 返回最新档案（重新查一次而不是拼，避免业务字段漏更）
        return Result.ok(getProfile(userId));
    } finally {
        lock.unlock();
    }
}
```

#### 该写什么、不该写什么

| 应该写 ✅ | 不该写 ❌ |
|---|---|
| 入参 / 出参的业务含义、单位、合法范围 | `// 给 i 赋值 0`（看代码就知道） |
| 为什么用这个算法 / 为什么加这一步（WHY） | `// PR-1234 修复` / `// 张三改的` |
| 隐藏约束（并发、时序、外部系统行为） | 整段被注释掉的旧代码（直接删，git 有历史） |
| 调用方要注意的副作用 | TODO 没人跟（要写就带名字 + 日期 + 工单号） |
| 块级编号 + 一句话概括每步在做啥 | 与代码不一致的过时注释（改代码必须同步改注释） |

#### 常用 Javadoc 标签速查

| 标签 | 用途 |
|---|---|
| `@param <名>` | 描述入参（含合法范围 / 是否可空） |
| `@return` | 描述出参（包括 null / 空集合的语义） |
| `@throws <异常>` | 描述会抛什么异常、什么场景抛 |
| `@deprecated` | 标记废弃 + 给替代方法链接 |
| `@see` | 关联到相关类 / 方法 |
| `{@link <类#方法>}` | 行内跳转引用 |

> 注释是给**未来的你和接手代码的人**看的。学员阶段宁可多写，等熟练后再判断哪些可省。

---

## 8. 测试与本地构建

### 8.1 单测

- 测 service / manager 的**纯逻辑**：业务分支、边界条件、异常处理。
- Mock 依赖（Mapper、远程 gRPC client）。
- 命名：`<Class>Test.<methodName>_<scenario>_<expected>()`，例 `UserProfileServiceTest.getProfile_whenUserDeleted_throwsBizException()`。

### 8.2 集成测试

- **Mapper 测试用真实 PG，不 mock DB**。本地起 PG container 或连共享 dev PG 的 `dating_dev_<yourname>` 库都行。
- 测试间数据隔离：用 `@Transactional` + 测试结束回滚，或在 `@BeforeEach` truncate。

### 8.3 本地构建

提交前必须本地跑通：

```bash
cd <service-name>
mvn -B -ntp clean package
```

不通过禁止 push。Jenkins 跑的是同一套命令。

### 8.4 提交格式

参考 [§3.3 Commit Message](#33-commit-messageconventional-commits)。conventional commits + `<service>` scope。

---

## 9. 提交 → 部署 → 排障

### 9.1 提交触发部署（本机 Jenkins）

整套都在**你本机**跑 —— 不连团队的 `jenkins.jianjiange.site`，你自己用 docker 起一个 Jenkins，让它从你 push 的 workspace 仓库拉代码、build 镜像、起容器。完整步骤见 [`dev-onboarding.md §8.3 / §8.4`](./dev-onboarding.md)。

```
本地写代码 + 改 proto
  ↓ proto 改了：在 proto/ 跑 mvn deploy / twine upload 推 Nexus
  ↓ 业务代码改了：mvn clean package 通过
git add . && git commit -m "feat(<svc>): ..."
git push origin <your>/<branch>
  ↓ 开 PR 到 main（或直接合并）
浏览器开 http://localhost:8081 (你本机的 Jenkins)
  ↓ 找到 dating-app 流水线，手动点 Build
本机 Jenkins 拉代码 → 跑 mvn → docker build → docker compose up -d
  ↓
你的本机 docker 网络 dating-app 里跑着 gateway / user / im / match...
连的是远端共享中间件（PG 38:5433 / Redis 38:6380 / Nacos 38:8848 / MinIO / RocketMQ / OpenIM）
  ↓
浏览器 / 安卓包访问 http://localhost:8080/<your-api>（gateway 暴出来的端口）
```

> **Jenkins 不靠 git push 自动跑**：push 完一定要去你本机 Jenkins UI 手动触发 Build，不然代码不会被构建。
>
> ⚠️ **每个学员一台本机 Jenkins，docker 网络 `dating-app` 也是本机的**，互相不会撞。

### 9.2 看日志（本机 Loki + Grafana）

服务日志一律打到 **stdout**（**禁写文件**），你本机的 Promtail 抓所有容器 stdout 推到本机 Loki，Grafana 查询。完整 compose 见 [`dev-onboarding.md §8.5`](./dev-onboarding.md)。

- 浏览器：**http://localhost:3000**（admin/admin，加 Loki 数据源 `http://loki:3100`）→ Explore → Loki
- 常用 LogQL：
  ```logql
  {container="dating-user-service"}                            # 单容器
  {container=~"dating-.*"} |~ "(?i)error|exception"            # 多容器 + 关键字
  sum(rate({container="dating-im-service"} |~ "ERROR" [5m]))   # 错误率
  ```
- 关键链路日志记得带 `traceId` / `userId`（MDC 透传），联调时按它过滤。

> 这套 Loki + Grafana 是你本机 docker 网络里的，**和团队的 `https://logs.jianjiange.site` 没关系**（那个是 154 业务机的容器日志聚合，不归学员管）。

### 9.3 排障决策树

| 现象 | 第一步 |
|---|---|
| 服务起不来 | 本机 Loki 看启动日志（§9.2 LogQL），重点找 `Caused by:` |
| Nacos 看不到服务 | 看 namespace 是否一致；本地 `dev-yourname` ≠ 别人的 namespace |
| gRPC 调用 Unavailable | 服务发现失败，确认目标服务实例在 Nacos 显示 Healthy；确认两个服务在同一 docker 网络 `dating-app` |
| 拉不到 proto 包 / `Could not find artifact` | Maven 仓配了 `maven-public`、Python 仓配了 `pypi-group`；包名带了 `<name>` 前缀；版本号锁对 |
| 发 proto 包报 `already exists` | 同版本只能发一次，升 `0.1.0` → `0.1.1` 再发 |
| PG 慢 | DataGrip 跑 `EXPLAIN ANALYZE`，看有没有走索引；多表 JOIN 拆单表 |
| Redis miss 率高 | 检查 TTL 是否过短、key 拼错；cache aside 是否漏删 |
| MinIO `SignatureDoesNotMatch` | 检查 `path-style-access: true` 是否开 |
| 本机 gateway 安卓包连不上 | 模拟器用 `10.0.2.2:8080`，真机用电脑局域网 IP（§8.6 dev-onboarding） |
| gateway 调到别人的服务上 | Nacos namespace 没切到你自己的 `dev-yourname`，重启服务 |
| OpenIM 消息发不出 | 看 `im-service` 日志，问题在 OpenIM REST 调用层 |

### 9.4 不允许的运维操作

- 🚫 不要 SSH 上共享服务器（38 / 154）改容器 / 改配置 / 改 nginx。
- 🚫 不要直接连共享 dev PG / Redis 写覆盖别人数据（每人各自的库 / db / key 前缀，§4.2）。
- 🚫 不要把 dev 凭据用到任何生产场景。
- 🚫 不要把团队的 `jenkins.jianjiange.site` / `logs.jianjiange.site` 当你的本机 Jenkins / Loki 用 —— 那两个是团队基建。

---

## 10. 红线汇总（违反一票否决）

| # | 红线 |
|---|---|
| 1 | 持久层出现多表 JOIN |
| 2 | 跨服务直连别人家的库表 / Redis key / 对象桶 |
| 3 | 服务间用 HTTP / FeignClient / RestTemplate 互调代替 gRPC |
| 4 | 真实密码 / token / AppKey / 私钥进 git |
| 5 | 引入清单外的中间件（ES / Mongo / ZK 等)未走评审 |
| 6 | 业务服务自建 WebSocket 长连，或绕开 `im-service` 直接调 OpenIM REST / 签 LiveKit Token / 持 OpenIM admin secret |
| 7 | 生产环境服务代码通过公网 IP 访问 PG / Redis / Nacos（必须用容器名 `prod-postgres:5432` 等） |
| 8 | DB 列用 `TIMESTAMP`（无时区）或代码 / SQL 里写死 `Asia/Shanghai` |
| 9 | 跨服务事务（XA / Seata / 本地事务挂远程调用） |
| 10 | controller 直接调 mapper / service 直接调别人 service 的 mapper |
| 11 | 把 Redis 当数据库（业务状态无法从 PG 重建） |
| 12 | 对外 API 暴露内部 `id` 自增主键（应用业务主键 `<entity>_id`） |

---

## 附录 A：example-service 骨架解读

`dating-server/example-service/` 是新服务的复制模板。结构：

```
example-service/
├── Dockerfile                                # 多阶段 maven:3.9-temurin-21 builder → temurin:21-jre-alpine runtime
├── pom.xml                                   # parent: spring-boot-starter-parent 3.3.5
├── .dockerignore
└── src/main/
    ├── java/com/dating/example/
    │   ├── ExampleApplication.java           # @SpringBootApplication 入口
    │   └── HelloController.java              # 一个返回 hello 字符串的 controller
    └── resources/
        └── application.yml                   # 端口、Nacos 占位等
```

### 新增服务步骤

```bash
cd dating-server
cp -r example-service <new-name>             # 1. 复制
mv <new-name>/src/main/java/com/dating/example \
   <new-name>/src/main/java/com/dating/<new>  # 2. 包改名
# 3. 手改：pom.xml 的 artifactId/name/description，*.java 的 package + import
# 4. deploy/docker-compose.dev.yml 追加 service block：
#    container_name: dating-<new>-dev
#    build context: ../<new-name>
#    端口走 .env.deploy 的 <NEW>_PORT
# 5. deploy/.env.deploy.example 追加 <NEW>_PORT=18xxx（找未被占用的）
# 6. Jenkinsfile.dev 的 choices 数组追加 '<new-name>'
git add . && git commit -m "feat(<new>): bootstrap service skeleton"
git push origin <your>/<branch>   # 然后开 PR
```

合并后到 Jenkins：**第一次必须无参 Build Now**（注册新 choice），再用 Build with Parameters 选 `<new-name>` 跑部署。

---

## 附录 B：推荐工具链

| 用途 | 工具 | 备注 |
|---|---|---|
| Java IDE | **IntelliJ IDEA**（Ultimate 优先） | 自带 Spring / Maven / DB 集成 |
| DB / Redis 客户端 | **DataGrip** | 一个工具看 PG + Redis；同 JetBrains 全家桶 |
| API 调试 | **Postman** / **Bruno** / `curl` | Postman 团队空间共享 collection |
| gRPC 调试 | **grpcurl** / **BloomRPC** / IDEA gRPC Plugin | 内部 gRPC 走 Nacos，调试用 hostname 临时指定 |
| MQ 调试 | RocketMQ Dashboard（https://rocketmq.jianjiange.site） | Topic 列表、消息查询、消费组监控 |
| 日志查询 | Grafana + Loki（本机 `http://localhost:3000`） | 跨容器 + 关键字过滤；部署见 dev-onboarding §8.5 |
| 服务监控 | Nacos 控制台 | 看哪些实例在线、配置版本 |
| Git 客户端 | 命令行 /  IDEA 内置 | 团队推荐命令行（流程清楚） |
| Markdown 预览 | IDEA / VSCode / Typora | 写设计文档 / PR description |
| 对象存储 GUI | MinIO 控制台 https://minio.jianjiange.site | 看桶、上传 / 下载 / 删对象、看对象元信息；够用，不用装命令行客户端 |

---

> 本文最后更新：2026-06-10
> 任何冲突以 `dating-server/CLAUDE.md` 红线为准。发现规范本身不合理 / 缺漏，提 PR 改本文 + 同步 CLAUDE.md。
