# post-service 技术方案

> 状态:草案 v0.2(全面重写)
> 目标读者:在自己的 `<yourpinyin>-workspace` 仓库下从零搭 `post-service` 的学员。
> 一切技术栈、基础设施、隔离前缀严格遵循 [`student-dev-guide.md`](./student-dev-guide.md);本文档不重复其中的通用规范,只补 post-service 自己的业务设计 / 数据模型 / 算法。
> 约定:全文 `<name>` 是你的拼音(`alice` / `bob` / `liming` ...),写代码 / 配 yaml / 拼 Redis key / 起 bucket 时统一替换。

---

## 0. 30 秒概览

| 项 | 取值 |
|---|---|
| 服务名 | `post-service`(Java 包 `com.dating.post`) |
| 端口 | HTTP `8080` / gRPC `9090`(本机不冲突即可) |
| Proto 坐标 | `com.dating.<name>.proto:post-proto:0.1.0`(发到共享 Nexus,见 `student-dev-guide §6.5`) |
| PG 库 | 共享 `dating_dev_<name>`,本服务 5 张表加 Flyway history `flyway_history_post` |
| Redis key 前缀 | `<name>:post:*` / `<name>:user:timeline:*` / `<name>:feed:*` / `<name>:lock:post:*` |
| MinIO bucket | `dating-<name>`(全 workspace 共用一个,本服务图片走 `post-image/` 前缀) |
| Nacos namespace | `dev-<name>`(配置 + 注册同 namespace) |
| 调用方 | `mobile-gateway`(REST→gRPC 转发) |
| 依赖方 | `user-service`(取好友列表 + 性别,gRPC + Nacos discovery) |
| MQ | 不引入(刷盘走 Redis Set + `@Scheduled`)|

---

## 1. 服务定位

UGC 内容服务,管三件事:

1. **帖子**(发 / 看 / 删,文本 ≤ 1024,图片 ≤ 9 张)
2. **互动**(点赞、一级评论,预留楼中楼字段)
3. **综合 Feed 流**(全网热门 + 好友写扩散 + 冷启动新帖三路混合;按性别分桶,异性优先)

**与其他服务的边界:**

| 关注问题 | 归属 |
|---|---|
| 帖子内容 / 图片 / 点赞 / 评论 持久化 | `post-service` |
| 帖子排序(全网热门 / 好友强插 / 冷启动)| `post-service` |
| 作者昵称 / 头像 / 性别 | `user-service`(`post-service` 只持 `user_id`,展示由 App 端拼接) |
| 「谁是我好友」 | `user-service`(发帖时由 `post-service` gRPC 拉一次,做写扩散) |
| 点赞 / 评论的 IM 通知 | `im-service`(本期不做,留扩展点;红线 6:绝不在 post-service 直调 OpenIM)|
| 公网入口 / JWT 鉴权 | `mobile-gateway`(post-service 信任 gateway 注入的 `user_id`,不自己解 JWT)|

---

## 2. 架构

```
            [App / H5]
                │ HTTPS
                ▼
       [mobile-gateway]   ── JWT 解出 user_id,注入 gRPC Metadata ──┐
                │ gRPC (Nacos: dev-<name>)                          │
                ▼                                                    │
   ┌──────────────────────────────────────────────────────────┐     │
   │  post-service  (Spring Boot 3.3.5 / MVC + gRPC)           │     │
   │  - 帖子读写 / 删除                                         │     │
   │  - 点赞(DB 幂等 upsert + Redis 增量)                       │     │
   │  - 评论(单表 + Redis ZSet 200 条窗口)                      │     │
   │  - Feed(性别分桶池 + 三路混合 + 布隆去重)                  │     │
   │  - 定时任务:LikeFlushJob / FeedScoreJob                   │     │
   └──┬─────────────┬────────────────┬───────────────────────────┘     │
      │             │                │                                 │
      ▼             ▼                ▼                                 │
  PostgreSQL    Redis 7         UserClient ─── gRPC (Nacos) ───────────┘
  共享 dev      共享 dev         调 user-service 取
  库=dating_    前缀=<name>:     - getFriendUserIds
  dev_<name>                    - getGenderByUserId (批量)
                                
   ┌─────────────────────────┐
   │ MinIO(共享 dev)         │ App presigned PUT 直传图片
   │ bucket=dating-<name>    │ post-service 只存 image_key
   │ key=post-image/{uid}/.. │
   └─────────────────────────┘
```

- 公网入口只有 `mobile-gateway`;`post-service` 自身不暴露公网,REST `Controller` 只为本机 curl 调试。
- 不引入 MQ;评论 / 点赞计数走 Redis Set + `@Scheduled` 刷盘。
- 不自建 WebSocket;若后续要做「点赞了你的帖子」推送,经 `im-service` 下发系统消息(红线 6)。

部署:本机 Jenkins 一条流水线 `mvn clean package` → `docker build` → `docker compose up -d`,容器跑在本机 docker 网络 `dating-app` 里,中间件全部连远端 `38.76.188.242`(见 `student-dev-guide §4 / §9.1`)。

---

## 3. 技术选型

严格按 `student-dev-guide §2`,这里只标注本服务多用到的依赖:

| 类别 | 选型 | 备注 |
|---|---|---|
| 语言 / 运行时 | JDK 21 | `eclipse-temurin:21-jre-alpine` |
| Web | Spring Boot 3.3.5 | parent `spring-boot-starter-parent` |
| ORM | MyBatis-Plus 3.5.9 | 单表 CRUD 走 BaseMapper / LambdaQueryWrapper;**禁多表 JOIN**(红线 1) |
| DB | PostgreSQL 16 | 列统一 `TIMESTAMPTZ`,连接 `SET TIME ZONE 'UTC'`(红线 8) |
| 迁移 | Flyway 10 | 本服务自己的 history 表 `flyway_history_post` |
| 缓存 | Redis 7 | key 前缀 `<name>:`(见 `student-dev-guide §6.2`) |
| 分布式锁 | Redisson | 锁 key 前缀 `<name>:lock:post:*` |
| 定时任务多实例互斥 | ShedLock(JDBC Provider,共用 PG `shedlock` 表)| 本机单实例可不接,部署多实例必须 |
| 对象存储 | MinIO,`dating-common` 的 `ObjectStorage` 接口 | bucket `dating-<name>`,`path-style-access: true`(`student-dev-guide §6.3`) |
| 配置 / 注册 | Nacos 2.4 | namespace `dev-<name>` |
| gRPC | grpc 1.68.1 + protobuf 4.28.3 + `net.devh:grpc-server-spring-boot-starter:3.1.0.RELEASE` | 服务发现 `discovery:///user-service` |
| Proto 依赖 | Nexus `com.dating.<name>.proto:post-proto:0.1.0` | 同版本号只能发一次 |
| 序列化 | `protobuf-java-util` | REST 出参直接序列化 proto message |

> ⚠️ 学员阶段引入清单**外**的中间件(MQ / ES / Mongo / ZK)需要在 PR 里说明并被评审。

---

## 4. 包结构

照 `student-dev-guide §5` 模板,这里给本服务实际类布局:

```
com.dating.post
├── PostApplication                # @SpringBootApplication + @EnableScheduling + @EnableAsync
├── grpc/
│   └── PostGrpcService             # 9 个 RPC 实现,只编排 service
├── controller/                     # 本机调试用,生产侧由 gateway 走 gRPC
│   └── PostController              # REST 1:1 映射 gRPC,@RestController
├── service/                        # 业务编排 + 事务边界
│   ├── PostWriteService            # 发帖 / 删帖
│   ├── PostReadService             # 详情 / 用户帖子列表
│   ├── LikeService                 # 点赞 upsert + Redis 增量
│   ├── CommentService              # 评论 增 / 删 / 列
│   ├── PostFanoutService           # @Async 写扩散
│   ├── FeedService                 # 池重建 + 三路混合读
│   ├── LikeFlushJob                # @Scheduled 每分钟刷盘点赞
│   ├── CommentFlushJob             # @Scheduled 每分钟刷盘评论
│   └── FeedScoreJob                # @Scheduled 每 5 分钟重建池
├── manager/                        # 单聚合 + 缓存读写
│   ├── PostManager                 # post + post_image 写
│   ├── PostStatManager             # post_stat 增量更新
│   ├── PostLikeManager             # post_like upsert
│   └── PostCommentManager
├── mapper/                         # 一张表一个 Mapper
├── entity/                         # 表 1:1
├── client/
│   └── UserClient                  # 调 user-service gRPC stub
├── config/
│   ├── SnowflakeIdGenerator        # 业务主键 post_id 生成
│   ├── RedisConfig                 # RedisTemplate + key 前缀
│   ├── RedissonConfig              # 分布式锁 + BloomFilter
│   └── GrpcClientConfig            # user-service stub 注入
├── constant/                       # PostStatus / LikeStatus / ErrorCode
└── exception/                      # BizException + GlobalExceptionHandler
```

调用方向严格单向:`grpc/controller → service → manager → mapper`(红线 10)。

---

## 5. 数据模型(PostgreSQL)

5 张表全部满足以下底线:

- 业务主键命名 `<entity>_id`(`post_id` / `comment_id`),不直接对外暴露内部自增 `id`(红线 12)。
- 时间列 `TIMESTAMPTZ`,默认 `CURRENT_TIMESTAMP`(红线 8)。
- `deleted SMALLINT DEFAULT 0` 配合 MyBatis-Plus `@TableLogic`。

### 5.1 `posts` — 帖子主表

| 列 | 类型 | 说明 |
|---|---|---|
| `id`         | `bigserial PK` | 内部物理主键,**不对外** |
| `post_id`    | `BIGINT UNIQUE NOT NULL` | 雪花 ID,跨库稳定的业务主键 |
| `user_id`    | `BIGINT NOT NULL` | 发帖人 |
| `content`    | `VARCHAR(1024) NOT NULL` | 文本 |
| `status`     | `SMALLINT DEFAULT 1` | 0=已删 / 1=正常 / 2=审核中 |
| `deleted`    | `SMALLINT DEFAULT 0` | 逻辑删除 |
| `created_at` | `TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP` | |
| `updated_at` | `TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP` | |

索引:
- `UNIQUE (post_id)`
- `(user_id, created_at DESC)` —— 「我的动态」分页

> 主表故意不放图片、点赞数、评论数(写放大率高的字段拆出去)。

### 5.2 `post_images` — 帖子图片

| 列 | 类型 | 说明 |
|---|---|---|
| `post_id`    | `BIGINT` | 业务主键引用 |
| `sort_order` | `SMALLINT` | 0..8 |
| `image_key`  | `VARCHAR(128)` | 对象存储 key,**不存 URL** |
| `created_at` | `TIMESTAMPTZ` | |
| PK | `(post_id, sort_order)` | 联合主键 |

设计意图:主键包含 `post_id` → 未来若需要按 `post_id` 范围分表/分区,分区键已在 PK 里。

### 5.3 `post_stats` — 计数底座

| 列 | 类型 | 说明 |
|---|---|---|
| `post_id`       | `BIGINT PK` | |
| `like_count`    | `INT DEFAULT 0` | 累计点赞(已刷盘部分) |
| `comment_count` | `INT DEFAULT 0` | 累计评论 |
| `updated_at`    | `TIMESTAMPTZ` | |

索引:`(like_count DESC)` / `(comment_count DESC)`,给未来「最热」榜单留口子。

**关键约束**:底座只存「已刷盘」部分。**实时值 = 底座 + Redis 增量**(§6.2)。

### 5.4 `post_likes` — 点赞幂等记录

| 列 | 类型 | 说明 |
|---|---|---|
| `user_id`    | `BIGINT` | |
| `post_id`    | `BIGINT` | |
| `status`     | `SMALLINT DEFAULT 1` | 1=已赞 / 0=已取消 |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | |
| PK | `(user_id, post_id)` | 联合主键 |

索引:`(post_id) WHERE status = 1` —— 反查「谁赞了这帖」,Partial Index 省空间。

**故意没有自增 ID**:联合主键既防重复点赞,又契合未来「按 user_id 分表」。
**故意不 DELETE,而是 UPDATE status = 0**:再次点赞时复用同一行,避免 INSERT 冲突。

### 5.5 `post_comments` — 评论(预留楼中楼)

| 列 | 类型 | 说明 |
|---|---|---|
| `id`               | `bigserial PK` | 内部 |
| `comment_id`       | `BIGINT UNIQUE NOT NULL` | 业务主键 = `id`(简单库可让两者等值),对外暴露 |
| `post_id`          | `BIGINT NOT NULL` | |
| `user_id`          | `BIGINT NOT NULL` | |
| `root_id`          | `BIGINT DEFAULT 0` | 根评论 ID(自身是根则为 0) |
| `parent_id`        | `BIGINT DEFAULT 0` | 直接父评论 ID |
| `reply_to_user_id` | `BIGINT DEFAULT 0` | 被回复人 |
| `content`          | `VARCHAR(512) NOT NULL` | |
| `status`           | `SMALLINT DEFAULT 1` | |
| `deleted`          | `SMALLINT DEFAULT 0` | |
| `created_at`       | `TIMESTAMPTZ` | |

索引:
- `(post_id, root_id, created_at DESC)` —— 一级评论分页(`root_id = 0`)
- `(root_id, created_at ASC)` —— 楼中楼按时间正序展开(为升级铺路)

初期所有评论都是 `root_id = parent_id = reply_to_user_id = 0`。升级楼中楼时**数据库零改动**,只在 service 层把字段填好,读侧多查一次「该根评论下子评论」。

### 5.6 Flyway

文件:`src/main/resources/db/migration/V20260615_01__init_post_tables.sql`

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    table: flyway_history_post     # 服务独占,不和别的服务的 history 撞
```

历史 migration 一旦合并不可改,要改只能加新文件(`student-dev-guide §6.1`)。

---

## 6. Redis 缓存设计

**全表 key 必须带 `<name>:` 前缀**,通过 `application-dev.yml` 的 `app.cache.key-prefix` 统一注入,业务代码用变量拼,不要散落硬编码(`student-dev-guide §6.2`)。

### 6.1 Key 全表

| Key Pattern | 类型 | TTL | 用途 |
|---|---|---|---|
| `<name>:post:detail:{post_id}` | Hash | 7d | 帖子详情(content + imageKeys + createdAt) |
| `<name>:post:stat:incr:{post_id}:likes` | String(Int) | 7d | 点赞未刷盘增量,正负皆可 |
| `<name>:post:stat:incr:{post_id}:comments` | String(Int) | 7d | 评论未刷盘增量 |
| `<name>:post:comments:{post_id}` | ZSet(score=comment_id) | 7d | 最新 200 条评论 |
| `<name>:post:updated_set` | Set | 7d | 待刷盘的 post_id 集合 |
| `<name>:user:timeline:{user_id}` | ZSet(score=epoch_s,最多 100 条) | 7d | 关注者时间线(写扩散) |
| `<name>:feed:pool:recommend:male` | ZSet(score=综合分,前 3000)| 7d | 男性用户看到的池(里面是女性发的帖)|
| `<name>:feed:pool:recommend:female` | ZSet | 7d | 同理,反性别 |
| `<name>:feed:cold_start:pool:male` | ZSet(score=epoch_s) | 7d | 男看的冷启动池 |
| `<name>:feed:cold_start:pool:female` | ZSet | 7d | |
| `<name>:user:read:bloom:{user_id}` | Redisson BloomFilter(容量 5000,误判 1%)| 7d | 已读去重 |

> ⚠️ 还有一张 `shedlock` 表(PG 不在本表),由 ShedLock 库管理,用于多实例部署时 `LikeFlushJob` / `CommentFlushJob` / `FeedScoreJob` 互斥。它**不是 Redis key**,本机单实例开发可以不接;部署多实例前必须接(否则 Job 重复跑浪费 PG 资源)。原理见 §6.5。

### 6.2 计数模型核心:写合并(Write Coalescing)

这是本服务**最重要的设计模式**,点赞、评论计数都用它。不理解这一节,后面的 `LikeFlushJob` / `CommentFlushJob` / 「DB 基准 + Redis 增量」都是天书。

#### 6.2.1 反面教材:直接 UPDATE post_stats

最直觉的做法,每次点赞:

```sql
UPDATE post_stats SET like_count = like_count + 1 WHERE post_id = 999;
```

**爆款场景:网红发帖,1 秒 1000 个人同时点赞**。PG 的 UPDATE 在事务提交前持有**行级排他锁**,这 1000 个 UPDATE 全部命中同一行 → **强制串行**:

```
请求 1   [获锁|读|写|fsync|放锁]  done @ 3ms
请求 2          等等等等等等等等等 [获锁|读|写|fsync|放锁]  done @ 6ms
请求 3                              等等等等等等等等等等等 [获锁...]  done @ 9ms
...
请求 1000                                                                   ... done @ 3000ms
```

灾难:
- 第 1000 个用户等 3 秒才返回(App 转圈)。
- PG 连接池(通常 10~20 个)全卡在这一行上,**发帖 / 查资料 / 登录全部连不上 DB**,故障扩散到整个服务。
- 上游 gateway timeout → retry → 又压到同一行,雪上加霜。

这就是「单点行锁打满整个 DB」,是任何高并发计数场景的第一道坎。

#### 6.2.2 我们的做法:Redis 累加 + 批量刷盘

**核心思路:把"高频写"和"持久化"在时间和介质上解耦**。

**写路径**(点赞接口):

```
1. post_likes 表 upsert(用户级幂等记录,每个 (user_id, post_id) 一行,不存在锁竞争)
2. INCR <name>:post:stat:incr:{post_id}:likes      ← 关键:计数只在 Redis 累加
3. SADD <name>:post:updated_set {post_id}          ← 标记"这帖有未刷盘的增量"
4. 返回,~1ms
```

**Redis 单 key INCR 是单线程内存原子操作,~50μs 一次**。1000 个并发点赞总耗时 ~50ms,且**不碰 PG**。

**刷盘路径**(`LikeFlushJob` 每 60 秒一次):

```
1. SRANDMEMBER <name>:post:updated_set 100   → 这分钟有变动的 100 个 post_id
2. 对每个 post_id:
     Lua: v = GET incr_key; SET incr_key 0; return v;   ← 原子取走 + 归零
     (比如这分钟攒了 1000 个赞,v = 1000)
3. UPDATE post_stats SET like_count = like_count + 1000 WHERE post_id = ?
                                                  ↑
                                          一次 UPDATE 顶 1000 次写
```

**1000 次点赞合并成 1 次 UPDATE**,PG 行锁只持有 ~5ms。

#### 6.2.3 读路径:DB 基准 + Redis 增量

那滞后 1 分钟,用户能看到实时点赞数吗?**能,因为读的时候在内存里加**:

```
读帖子详情:

实时 likes  =  post_stats.like_count      +      GET <name>:post:stat:incr:{post_id}:likes
               ↑                                 ↑
               「已刷盘基准值」                    「未刷盘增量」
               例 100                            例 1000

前端看到:100 + 1000 = 1100  ← 永远是最新的
```

刷盘后状态自动一致:

```
刷盘前:   post_stats.like_count = 100,   Redis incr = 1000   →   读到 1100
刷盘中:   UPDATE += 1000,Lua 把 Redis 归零(原子先后顺序由 Job 控制,不重要)
刷盘后:   post_stats.like_count = 1100,  Redis incr = 0      →   读到 1100  ✅
```

**用户从不感知刷盘节奏**,数字不会跳变,也不会"先少后多"。Redis 在这里**不是缓存(cache)**,而是**「未刷盘 delta 累加器」**:DB 是真相,Redis 是中间窗口。即使 Redis 全挂,DB 基准值也是真值,只是丢了这一分钟的增量(产品上可接受,且监控可发现)。

#### 6.2.4 为什么 Lua 不能拆成两条命令

刷盘脚本必须是 Lua,**不能**写成 `GET` + `SET 0` 两次客户端调用:

```
错误做法:
  v = redis.GET(incr_key)        ← 假设拿到 1000
  ※ 此刻有用户点赞 → INCR → 1001 ※
  redis.SET(incr_key, 0)         ← 把 1001 也覆盖掉了!这个赞永远丢了

正确做法(Lua):
  EVAL "local v = redis.call('GET', KEYS[1]); redis.call('SET', KEYS[1], 0); return v;"
  ※ Redis 单线程,Lua 脚本期间不接受任何其他命令 ※
  → GET 和 SET 之间不可能有别的 INCR 插入 → 0 丢失风险
```

#### 6.2.5 这个模式叫什么、什么时候用

**写合并 (Write Coalescing / Write Batching)**,业界几乎所有高并发计数场景都是这套:微博点赞、视频播放数、商品浏览数、文章阅读量。

适用条件:
- 写流量 ≫ 读流量,且写在**少数热点 key** 上(同帖被反复点赞,而不是均匀分散)。
- 计数**可短暂滞后**(评论数延迟 1 分钟用户无感)。
- 不需要**实时精确触发阈值**(例如"满 100 赞自动加精"这种就不适合 Job 异步,要在 INCR 后用 Lua 比较)。

不适用:
- 钱(余额)。钱必须是 DB 主真值 + 强一致事务,**绝不能**走 Redis 累加。

> **核心一句话**:PG 不擅长高频改同一行(行锁串行 + WAL fsync),Redis 擅长(单线程内存 atomic)。让 Redis 扛写流量算增量,让 PG 承担批量合并后的稀疏更新。读时两边加,对用户就是实时。

### 6.3 评论 ZSet 的「最新 200 条窗口」

90% 用户只看最新评论的前几屏,Redis ZSet 挡住绝大多数读流量。翻到第 200 条之后才回源 DB,走 `(post_id, root_id, created_at DESC)` 索引,**回源不会回写 ZSet**(因为 ZSet 永远只保证「最近 200 条」窗口的连续性,回写老数据会破坏裁剪 invariant)。

### 6.4 性别分桶的池设计(约会类产品强需求)

异性优先:男看女、女看男。`feed:pool:recommend` / `feed:cold_start:pool` 都拆 `:male` / `:female`,池里装**该性别用户发的帖**,读侧按当前用户性别取异性池。

构造细节(谁写、何时写、怎么裁剪、怎么切换)见 §10.2「三路池的构造与维护」。

### 6.5 ShedLock:多实例定时任务互斥

本服务有 3 个 `@Scheduled` Job(`LikeFlushJob` / `CommentFlushJob` / `FeedScoreJob`)。**单实例部署完全不需要 ShedLock**,直接 `@Scheduled` 就行。

部署 2 个及以上实例时(高可用 / 灰度发布并存)就有问题:两个实例都按 `fixedRate=60_000` 触发,会**重复跑同一个 Job**:

```
T=0s   实例 A: SRANDMEMBER updated_set 100 → [99,98,97...]
              Lua GET → 1000, SET 0
              UPDATE post_stats += 1000
T=0s   实例 B: SRANDMEMBER updated_set 100 → 可能同样的 ID
              Lua GET → 0 (刚被 A 清掉)
              UPDATE post_stats += 0     ← 浪费 PG
              或 SREM 在事务交错下漏删 → updated_set 越积越大
```

不致命,但浪费 + 难排查。**ShedLock 保证同名 Job 同一时刻全集群只有一个实例跑**。

#### 用法

```java
@Scheduled(fixedRate = 60_000)
@SchedulerLock(name = "post.likeFlush",
               lockAtMostFor  = "PT2M",    // 兜底:进程崩了 2 分钟后锁自动失效
               lockAtLeastFor = "PT5S")    // 防抖:不管 Job 跑多快至少持锁 5 秒
public void flushLikes() { ... }
```

#### 它的存储:PG 一张表(JDBC Provider)

```sql
CREATE TABLE shedlock (
    name        VARCHAR(64)   PRIMARY KEY,
    lock_until  TIMESTAMPTZ   NOT NULL,
    locked_at   TIMESTAMPTZ   NOT NULL,
    locked_by   VARCHAR(255)  NOT NULL
);
```

抢锁流程(全部由 ShedLock 库自动做):

```
1. 实例 A: INSERT INTO shedlock (name='post.likeFlush', lock_until=now+2min, ...)
            主键不存在 → 成功 → 拿锁,跑 Job
2. 实例 B (同一秒触发):
            UPDATE shedlock SET ... WHERE name='post.likeFlush' AND lock_until < now
            lock_until 还没过 → WHERE 不成立 → 0 行受影响 → 跳过本次
3. A 跑完: UPDATE shedlock SET lock_until = now + lockAtLeastFor → 释放
4. 1 分钟后 B 再触发,lock_until 早过了 → 成功抢到 → B 跑
```

#### 为什么选 JDBC Provider 不选 Redis Provider

ShedLock 支持多种 Provider(JDBC / Redis / ZooKeeper),都行。我选 JDBC 的理由:

- 学员服务已经依赖 PG,**少一个组件依赖**(Redis Provider 还需要 `shedlock-provider-redis-spring`)
- 锁状态在 PG 也方便 `SELECT * FROM shedlock` 排查
- 每分钟只抢一次锁,JDBC 的开销可以忽略

> 学员阶段开发期单实例跑,**ShedLock 注解可以先加上但不接 Provider**(注解装饰性,不影响功能)。部署到 docker compose 同时跑 2 个 replicas 之前再补依赖 + Flyway migration 建 shedlock 表。

---

## 7. 对象存储(MinIO)

照 `student-dev-guide §6.3` 接 MinIO,要点:

| 项 | 值 |
|---|---|
| endpoint | `https://minio-api.jianjiange.site` |
| `path-style-access` | **true** |
| bucket | `dating-<name>`(**workspace 唯一一个,所有服务共用**) |
| 本服务 object key 前缀 | `post-image/` |
| Key 完整格式 | `post-image/{user_id}/{yyyymm}/{uuid}.{ext}` |
| 临时上传 | `tmp/post-image/...`,bucket Lifecycle 24h 自动清 |

### 7.1 共享桶 + 前缀隔离的设计

**一个学员的整个 workspace 只开一个桶 `dating-<name>`**,所有服务共用,**不同服务靠 object key 的顶层前缀隔离**:

```
dating-<name>/
├── avatar/{user_id}/{yyyymm}/...      ← user-service 写的头像
├── post-image/{user_id}/{yyyymm}/...  ← post-service 写的帖子图
├── attachment/{user_id}/{yyyymm}/...  ← im-service 写的私聊附件
├── id-card/{user_id}/...              ← user-service 写的实名认证(私有 + 加密)
└── tmp/...                            ← 所有服务的中转上传,24h Lifecycle 清
```

**前缀就是服务边界**。本服务只往 `post-image/` 写,别的服务负责自己的前缀。

#### 为什么不再分 `dating-user` / `dating-post` 等多桶

学员阶段:

1. **MinIO 控制台手动建一堆桶费事**,每加一个服务就要去 Web 点一次。
2. **AK/SK 共用一对**(走 Nacos),哪怕分桶也没法做"user-service 只能读 dating-user"的细粒度授权 —— 没意义。
3. **Lifecycle / 监控 / 备份**一桶一套规则,共用一桶只配一次。
4. **将来真要切到团队多桶模式**,只需要把 endpoint + bucket 名换到不同环境,业务代码里**只动 `application.yml` 那一行 `bucket`**,因为本服务始终只用 `post-image/` 前缀 —— 换桶等价于换路径根。

> 团队生产环境是「一服务一桶」+ 对应每桶独立 IAM Policy,但那需要专人维护权限。学员阶段共享桶足够,迁移成本几乎为零。

### 7.2 上传职责切分

| 阶段 | 谁干 | 怎么干 |
|---|---|---|
| App 选图 | 客户端 | 拍照 / 相册 |
| 申请 presigned PUT URL | App → `mobile-gateway` → `post-service.PrePost` 或专门的上传服务 | 后端校验 ext(jpg/jpeg/png)、大小(≤ 30 MB by PUT policy),返回 URL + 预期 key |
| 直传 | App ↔ MinIO | 后端不读文件流 |
| 创建帖子 | App → `post-service.CreatePost(content, [image_key])` | 后端校验 key 数量 ≤ 9,落 `post_images` |
| 展示 | App | `${cdnBaseUrl}/dating-<name>/{image_key}` 自拼(公开资产)|

### 7.3 出参契约(`student-dev-guide §6.3 出参契约`)

- VO / gRPC 出参一律回 `image_key`(字段名带 `_key`)。
- **服务端绝不拼 URL 返回**(CDN / endpoint / bucket 改了,App 改一行配置就行)。
- **绝不**给自己签 GET URL 自己消费(presigned URL 只给无凭据的 App / H5;红线见 student-dev-guide §6.3 Presigned URL 适用范围)。

### 7.4 跨服务取图原则

workspace 内所有服务**共享同一个桶**,因此红线 2 在这里的含义是:**不直接读别的服务的 key 前缀**(即使你 AK/SK 一样、技术上能读)。如果将来某个服务需要消费帖子图片(例如审核 / 内容推荐 / 缩略图生成):

- 调用方调 `post-service.GetPostDetail` 拿到 `image_keys`(就是 `post-image/...` 这种 key)。
- 公开资产 → 调用方按 `${cdnBaseUrl}/dating-<name>/{key}` 自拼 URL 直接拉。
- 私有 / 敏感资产 → 在 post-service 的 proto 里新增一个 RPC(例 `GetPostImageBytes(post_id, sort_order) → bytes` 或 `BatchPresignPostImages`),由 post-service 走 `ObjectStorage.getObject` 读出后透传 bytes / 签 presigned GET URL。

**关键**:调用方**绝不绕过 post-service 直接以 `post-image/...` 前缀 `getObject`**。原因:

1. **业务校验绕过**:post-service 的 `GetPostDetail` 会校验帖子是否被删 / 是否被作者隐藏 / 调用方是否有权看,直接读 key 等于绕过这些。
2. **耦合反向**:谁都能读 `post-image/` 后,post-service 改前缀格式时全 workspace 都得跟着改。
3. **审计混乱**:MinIO access log 只能看到 AK 是谁,看不到"是哪个服务在读"。走 gRPC 就能在 post-service 的日志里留痕。

> 本期没有任何这样的消费方,先不实现;留到真有需求再加 RPC。

---

## 8. gRPC 接口设计

proto 放在 workspace 根的 `proto/post/post.proto`,在 `proto/` 下 `mvn deploy` 推到 Nexus,坐标 `com.dating.<name>.proto:post-proto:0.1.0`(`student-dev-guide §6.5`)。

### 8.1 9 个 RPC

| RPC | 入参核心字段 | 出参 | 鉴权来源 |
|---|---|---|---|
| `CreatePost` | content, image_keys | post_id | gRPC Metadata 注入的 user_id |
| `GetPostDetail` | post_id | PostInfo | Metadata user_id(可空)|
| `ListUserPosts` | user_id(目标), page_size, cursor | items, next_cursor, has_more | Metadata user_id(当前查看者)|
| `ActionLike` | post_id, action(LIKE/UNLIKE) | success | Metadata user_id |
| `CreateComment` | post_id, content, root_id, parent_id | comment_id | Metadata user_id |
| `ListComments` | post_id, page_size, cursor(=comment_id) | comments, next_cursor, has_more | Metadata user_id |
| `DeleteComment` | comment_id | success | Metadata user_id(必须 == 评论作者) |
| `DeletePost` | post_id | success | Metadata user_id(必须 == 帖子作者) |
| `GetRecommendFeed` | page_size, cursor("rec:cs") | items, next_cursor, has_more | Metadata user_id |

**统一返回包装**:proto 里 `BaseResponse{code, message, extra}`。业务异常由 `GlobalExceptionHandler` 兜底转 `BaseResponse(code != 0)`。HTTP/gRPC 状态码统一 OK,业务错误靠 code 区分。

**user_id 透传方式**:`mobile-gateway` 解 JWT 得到 user_id 后塞进 gRPC Metadata(key=`x-user-id`),本服务用 `ServerInterceptor` 在每个 RPC 入口取出来注入 `Context` / MDC。**禁止把 user_id 作为业务字段塞 proto request 里**(除非是「操作目标」如 `ListUserPosts` 的 `user_id` 是查谁的帖子,这是合法用法)。

### 8.2 错误码

| code | 含义 |
|---|---|
| 0 | OK |
| 4001 | content 为空 |
| 4002 | content 超长 |
| 4003 | 图片数量超限 |
| 4004 | 图片 key 为空 |
| 4005 | 帖子不存在 |
| 4006 | 评论不存在 |
| 4007 | 评论内容为空 |
| 4008 | 评论内容超长 |
| 4030 | 权限不足 |
| 5000 | 内部错误 |

REST 兜底:全局 `@RestControllerAdvice` 把 `BizException` 转 `Result{code, message, data}`,HTTP 200。

---

## 9. 核心业务流程

### 9.1 发布帖子(CreatePost)

```
App ─ presigned PUT 图片 ─→ MinIO
  ↓
App ─ CreatePost(content, [image_key]) ─→ post-service
   1. 入口校验:1 ≤ len(content) ≤ 1024,len(image_keys) ≤ 9
   2. snowflake.nextId() → post_id
   3. @Transactional:
      - INSERT posts
      - 循环 INSERT post_images(sort_order = i)
      - INSERT post_stats(0, 0)
   4. Redis HSET <name>:post:detail:{post_id}(TTL 7d)
   5. ZADD <name>:feed:cold_start:pool:{发帖人性别}
      (gender 由 UserClient.getGenderByUserId 拿,带 Caffeine 30s 本地缓存避免热点)
   6. @Async PostFanoutService.fanoutToFollowers:
      - UserClient.getFriendUserIds(userId)
      - for each follower: ZADD <name>:user:timeline:{follower},裁剪到 100,TTL 7d
   7. 返回 post_id
```

**事务边界只覆盖步骤 3** —— Redis 写、冷启动池写、写扩散都是事务外的 best-effort,失败不回滚 DB(帖子已落库,后续 5 分钟池重建会兜底)。

### 9.2 删帖(DeletePost)

1. SELECT 校验:存在 + 未删 + `userId == post.user_id`(权限)
2. UPDATE `posts.deleted = 1`
3. DEL `<name>:post:detail:{post_id}`
4. ZREM 冷启动池里这条
5. SREM `<name>:post:updated_set`

> ⚠️ 不删 `post_likes` / `post_comments` 历史(留审计),不删 `user:timeline:*` 里的 member(成本太高;读侧拿到死 post_id 时 `getPostDetail` 抛 `POST_NOT_FOUND`,FeedService try-catch warn 后跳过)。

### 9.3 点赞 / 取消(ActionLike)

PostgreSQL upsert(`PostLikeMapper.xml`):

```sql
INSERT INTO post_likes (user_id, post_id, status, created_at, updated_at)
VALUES (?, ?, ?, NOW(), NOW())
ON CONFLICT (user_id, post_id)
DO UPDATE SET status = EXCLUDED.status, updated_at = NOW()
WHERE post_likes.status <> EXCLUDED.status;
```

- 影响行数 = 0(已是目标状态)→ 幂等,直接 return。
- 影响行数 = 1(状态真变了)→ Redis:
  - INCR/DECR `<name>:post:stat:incr:{post_id}:likes`(+1 / -1)
  - SADD `<name>:post:updated_set` post_id

> **为什么不直接 UPDATE post_stats?** 见 §6.2「写合并」—— 爆款帖单行锁打满会拖垮整个 PG。Redis 累加 + 1 分钟批量刷盘把 1000 次 UPDATE 合并成 1 次。

### 9.4 LikeFlushJob(每 1 分钟,ShedLock 多实例互斥)

```
1. SRANDMEMBER <name>:post:updated_set 100  (Spring distinctRandomMembers)
2. for each post_id:
     Lua: local v = redis.call('GET', KEYS[1])
          redis.call('SET', KEYS[1], 0)
          return v;
     // 原子拿走 + 归零,期间新点赞写回 1 不会丢
     UPDATE post_stats SET like_count = like_count + ? WHERE post_id = ?
3. SREM <name>:post:updated_set 这批 post_id
```

关键点:
- Lua 「GET + SET 0」原子,绝不丢用户毫秒内的新点赞。
- `UPDATE ... like_count = like_count + delta`(增量加法非覆盖),Job 之间乱序、重叠都不影响结果。
- ShedLock `@SchedulerLock(name="likeFlush", lockAtMostFor="PT2M")` 防多实例同时跑导致重复 SPOP。

### 9.5 创建评论(CreateComment)

1. validateComment(content,1-512,trim)
2. INSERT `post_comments`(`root_id=0, parent_id=0, reply_to_user_id=0`)
3. ZADD `<name>:post:comments:{post_id}` score=comment_id member=comment_id;ZREMRANGEBYRANK 裁剪到 200
4. Redis INCR `<name>:post:stat:incr:{post_id}:comments`
5. SADD `<name>:post:updated_set`(让 `CommentFlushJob` 拾起)

### 9.6 列表评论(ListComments,游标分页)

```
cursor = N(上一页最末的 comment_id),首次传 0
1. ZSet 有数据 → reverseRangeByScore(zsetKey, -inf, N-1, 0, pageSize)
2. 拿到 comment_id 列表 → 逐个 getById(命中 Redis 缓存里都是热数据)
3. ZSet 空(冷帖 或 翻到 200 之外)→ DB:
     SELECT * FROM post_comments
     WHERE post_id = ? AND root_id = 0 AND deleted = 0
       AND comment_id < ?
     ORDER BY created_at DESC LIMIT ?
4. 返回 + next_cursor = items.last.comment_id, has_more = items.size == pageSize
```

### 9.7 删除评论(DeleteComment)

1. 权限校验:必须是评论作者
2. UPDATE `post_comments.deleted = 1`
3. ZREM `<name>:post:comments:{post_id}` member=comment_id
4. DECR `<name>:post:stat:incr:{post_id}:comments`,SADD updated_set

### 9.8 推荐 Feed(GetRecommendFeed)

```
cursor = "recOffset:csOffset"(首次 "0:0")
gender = userClient.getGenderByUserId(currentUserId)
oppositeSex = !gender   // 异性优先

并行三路:
  ① ZREVRANGE  <name>:feed:pool:recommend:{oppositeSex}      [recOffset, recOffset+30]
  ② ZREVRANGEBYSCORE  <name>:user:timeline:{currentUserId}    最近 7 天,0,5
  ③ ZREVRANGE  <name>:feed:cold_start:pool:{oppositeSex}     [csOffset, csOffset+10]

布隆过滤:
  Redisson BloomFilter <name>:user:read:bloom:{currentUserId}
  → 三路所有 ID 过滤一遍

混排(FeedService.mergeThreeWay):
  位置  1, 2, 4, 5, 7, 8, 9, 10 → recommend(降级 cold_start → friend)
  位置  3                       → friend(强插,降级 recommend)
  位置  6                       → cold_start(新帖扶持,降级 recommend)

  同好友频控:单页同一个好友最多 1 条

拼装:
  for each post_id: postReadService.getPostDetail(...) (try-catch 跳过已删)
  for each returned id: bloom.add(id.toString())
  next_cursor = (recOffset + pageSize) : (csOffset + 1)
```

---

## 10. Feed 推荐算法

### 10.1 时间衰减打分(Hacker News 变体)

```
Score = (W_base + α · likeCount + β · commentCount) / (hoursSincePublished + 2) ^ 1.5

  W_base = 10.0   # 冷启动扶持分,防新帖 0 分
  α = 1.0         # 点赞权重
  β = 3.0         # 评论权重(交互成本高,更稀缺)
  指数 1.5         # 时间衰减:24h 后分数 ≈ 1/25
```

每 5 分钟 `FeedScoreJob` 全量重建:近 3 天所有 status=1 帖 ≤ 几万条,内存里算完无压力。

### 10.2 三路池的构造与维护

Feed 读时同时取 3 路数据 (§10.3),这 3 路池子由 3 套**独立**机制各自维护,**互不依赖**(任意一路挂了,读侧降级到另两路 + 兜底)。

| 池 | 写入触发 | 读侧用途 | 排序依据 | 性别分桶 |
|---|---|---|---|---|
| ① 全网热门池 `feed:pool:recommend:{gender}` | `FeedScoreJob` @Scheduled 5min | 主力填充大多数位置 | Hacker News 热度分 | ✅ |
| ② 好友时间线 `user:timeline:{user_id}` | 发帖时 `@Async` 写扩散 | 第 3 位强插好友 | 时间倒序 | ❌ (per-user) |
| ③ 冷启动池 `feed:cold_start:pool:{gender}` | 发帖时**同步** ZADD | 第 6 位扶持新帖 | 时间倒序 | ✅ |

---

#### 10.2.1 池 ① 全网热门池(pull 模式 / Job 主动拉)

**目的**:全网近 3 天优质帖按热度打分,排前 3000 进池,做主力候选。

**触发**:`FeedScoreJob @Scheduled(fixedRate=300_000)`,每 5 分钟全量重建。多实例部署靠 ShedLock 互斥 (§6.5)。

**完整构造流程**:

```
┌── Step 1: 候选集捞取(PG,单表两次查)──────────────────────┐
│  posts:                                                    │
│    SELECT post_id, user_id, created_at FROM posts           │
│    WHERE deleted=0 AND status=1                              │
│      AND created_at >= NOW() - INTERVAL '3 days';            │
│    // 近 3 天 ≤ 几万条,一次性捞                              │
│                                                              │
│  post_stats(批量):                                          │
│    postStatsMapper.selectBatchIds(postIds)                  │
│    // selectBatchIds 内部 WHERE post_id IN (...) 单表查      │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌── Step 2: 实时计数补偿(读 Redis 增量)──────────────────────┐
│  for each post_id:                                          │
│    likeIncr    = GET <name>:post:stat:incr:{pid}:likes      │
│    commentIncr = GET <name>:post:stat:incr:{pid}:comments   │
│    likes    = stat.likeCount    + likeIncr                  │
│    comments = stat.commentCount + commentIncr               │
│    // 不补偿就会用「上一次刷盘后的旧值」打分,排序失真         │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌── Step 3: 内存打分(Hacker News 变体)──────────────────────┐
│  for each post:                                             │
│    hoursDiff = (now - created_at_epoch) / 3600.0            │
│    score = (10 + 1.0 * likes + 3.0 * comments)              │
│              / Math.pow(hoursDiff + 2, 1.5)                 │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌── Step 4: 性别分桶(批量 RPC + Caffeine 30s 缓存) ──────────┐
│  distinctUserIds = posts.stream.map(userId).distinct        │
│  genderMap = userClient.getGenders(distinctUserIds)         │
│  // ⚠️ 必须批量!单条调 N 次会把 user-service 压垮            │
│  // 本地 Caffeine 30s 缓存吸收同一用户的多帖重复查询           │
│                                                              │
│  for each post:                                             │
│    if genderMap[post.userId] == MALE:                       │
│        zaddBatch.put("...:male:tmp",   post.postId, score)  │
│    else:                                                    │
│        zaddBatch.put("...:female:tmp", post.postId, score)  │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌── Step 5: 影子写 tmp ZSet + 裁剪 + TTL ─────────────────────┐
│  // tmp key 先清掉残留(上次 Job 没跑完?异常?)              │
│  DEL <name>:feed:pool:recommend:male:tmp                    │
│  DEL <name>:feed:pool:recommend:female:tmp                  │
│                                                              │
│  // 一次性 ZADD 全部                                         │
│  ZADD <name>:feed:pool:recommend:male:tmp  (score, postId)…│
│  ZADD <name>:feed:pool:recommend:female:tmp ...             │
│                                                              │
│  // 裁剪到前 3000(分数从低到高排,砍掉 0 ~ size-3001)        │
│  size = ZCARD ...:male:tmp                                  │
│  if size > 3000:                                            │
│      ZREMRANGEBYRANK ...:male:tmp 0 (size - 3001)           │
│                                                              │
│  EXPIRE ...:male:tmp 7d                                     │
│  EXPIRE ...:female:tmp 7d                                   │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌── Step 6: 原子 RENAME(无缝切换)─────────────────────────────┐
│  RENAME <name>:feed:pool:recommend:male:tmp                 │
│      → <name>:feed:pool:recommend:male                      │
│  RENAME <name>:feed:pool:recommend:female:tmp               │
│      → <name>:feed:pool:recommend:female                    │
│                                                              │
│  // RENAME 是 Redis 原子操作,读侧绝不会读到半写状态           │
│  // 旧 key 自动被覆盖,旧池里那 3000 个 post_id 整体过期不用清理│
└──────────────────────────────────────────────────────────────┘
```

**为什么 5 分钟全量重建,而不是发帖/点赞时实时改分**:

- Hacker News 公式有时间衰减项 `(hoursDiff + 2)^1.5`,意思是**每条帖的分数随时间被动跌**,即使没人点赞。要实时维护就得跟着秒级时钟跑,所有 3000 个 post_id 每秒重排,得不偿失。
- 5 分钟延迟用户感知不到「这条帖排第 12 名而不是第 10 名」,但能省掉天量写操作。
- 全量重建一次 ≤ 1 秒级(几万行 SELECT + 内存算 + 一把 ZADD),对 PG / Redis 都没压力。

**读侧使用**:用户性别 = MALE → 取 `feed:pool:recommend:female`(异性优先)。

**失败模式**:Job 卡死 → 池仍是上次成功重建的快照,读侧不报错,只是数据陈旧。监控 `feed.score.rebuild.duration` Timer + alert if 超过 2 分钟。

---

#### 10.2.2 池 ② 好友时间线(push 模式 / 写扩散)

**目的**:每个用户有一份独立 timeline,装「他关注的人最近发的帖」。Feed 第 3 位强插。

**为什么写扩散(push)而不是读扩散(pull)**:

| 维度 | 写扩散(选)| 读扩散 |
|---|---|---|
| 发帖成本 | O(N 个关注者),要 ZADD N 次 | O(1),只写自己 timeline |
| 读 Feed 成本 | O(1) `ZREVRANGE` 单 key,~毫秒 | O(M 个关注的人) ZUNIONSTORE,慢 |
| 适合场景 | **关注数有上限**(约会 App 好友 ≤ 几百)| 关注数无上限(微博明星粉丝过亿)|

约会场景关注数小、读 Feed 要极快 → 选写扩散。

**触发**:`PostWriteService.createPost` 完事务后异步触发 `PostFanoutService.fanoutToFollowers @Async`。

**完整构造流程**:

```
┌── Step 1: 拉关注者列表(gRPC)──────────────────────────────┐
│  followers = userClient.getFriendUserIds(发帖人 userId)     │
│  // 拿到的是"关注发帖人的所有 user_id"                       │
│  // user-service down → 返空,本次 fanout 直接 no-op           │
│  // (容错降级:这帖的好友通道断了,但全网池/冷启动池兜底)      │
│                                                              │
│  if followers.isEmpty(): return;                            │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌── Step 2: 逐个 ZADD 关注者的 timeline ──────────────────────┐
│  score = postCreatedAt.toEpochSecond()                      │
│  for each follower in followers:                            │
│    key = <name>:user:timeline:{follower}                    │
│                                                              │
│    ZADD key (score, postId)                                 │
│                                                              │
│    // 裁剪到最近 100 条,防 timeline 无限膨胀                  │
│    size = ZCARD key                                         │
│    if size > 100:                                           │
│        ZREMRANGEBYRANK key 0 (size - 101)                   │
│        // 从 score 最低(最老)那端砍                          │
│                                                              │
│    EXPIRE key 7d                                            │
│                                                              │
│  log.info("Fanout complete: postId={} followers={}", ...)   │
└──────────────────────────────────────────────────────────────┘
```

**为什么 score 用 epoch 而不是热度分**:

timeline 的产品语义是「按时间看好友最近发啥」,读时直接 `ZREVRANGEBYSCORE` 取最新 N 条即可。热度排序由全网热门池 ① 负责,timeline 不重复算。

**为什么 timeline 不分性别**:

timeline 是 per-user 的私有视图,关注谁是用户自己定的,服务端不再二次分桶。

**失败模式**:

| 场景 | 后果 | 兜底 |
|---|---|---|
| `@Async` 抛异常 | 该 follower 缺这一条 | 最多 5 分钟后,全网热门池 ① 重建覆盖这条,用户能从主力位看到 |
| user-service down | fanout 直接 no-op | 同上,主力池兜底 |
| 关注关系变更 | 老 timeline 不回溯重写 | 新关注 → 从新发帖开始入 timeline;想看旧帖去 user-service 拉 TA 的帖子列表 |

**裁剪策略**:`ZREMRANGEBYRANK key 0 size-101` 从最低 score(最老)那端砍,留最近 100 条。100 条相当于覆盖近 100 个好友帖,对 Feed 第 3 位强插(每页用 ≤ 1 条)绰绰有余。

---

#### 10.2.3 池 ③ 冷启动池(同步 push / 发帖瞬间)

**目的**:解决「新帖刚发出来 likeCount=0,Hacker News 公式打出来的分排不进 Top 3000,永远没人看到」的马太效应。冷启动池**不按热度排只按时间**,任何新帖立刻入池。

**触发**:`PostWriteService.createPost` 流程的第 5 步,事务提交后**同步** ZADD(不走 `@Async`,因为只是一个 ZADD 操作,毫秒级,没必要异步)。

**完整构造流程**:

```
┌── Step 1: 判发帖人性别 ─────────────────────────────────────┐
│  isMale = userClient.isMale(发帖人 userId)                  │
│  // 命中 Caffeine 30s 缓存,~微秒                             │
│                                                              │
│  key = isMale ? <name>:feed:cold_start:pool:male            │
│              : <name>:feed:cold_start:pool:female           │
│  // 池里装"该性别发的帖",读侧按当前用户取异性池               │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌── Step 2: ZADD + TTL ───────────────────────────────────────┐
│  ZADD key (score = epoch_seconds, member = postId)          │
│  EXPIRE key 7d                                              │
│                                                              │
│  (可选裁剪,防极端膨胀)                                       │
│  size = ZCARD key                                           │
│  if size > 10_000:                                          │
│      ZREMRANGEBYRANK key 0 (size - 10_001)                  │
│  // 读侧只取前 10 条,10000 上限纯防 OOM 兜底                 │
└──────────────────────────────────────────────────────────────┘
```

**与全网热门池 ① 的协同**:

| | ① 全网热门池 | ③ 冷启动池 |
|---|---|---|
| 准入门槛 | 跑下来分数前 3000 | **任何新帖立刻进** |
| 排序 | 热度分 | 纯时间 |
| 更新延迟 | 最多 5 分钟 | **0 延迟**(发帖时同步写) |
| Feed 位置 | 位置 1/2/4/5/7/8/9/10 | 位置 6 |

冷启动池保证「**新帖在前 5 分钟内**(全网池还没重建)就有曝光位」,等下一次全网池重建,如果这条新帖真的火,就同时进 ① 池;不火也能在 ③ 池窗口内挣到一些曝光。

---

#### 10.2.4 三路池的对比汇总

| 维度 | ① 全网热门池 | ② 好友时间线 | ③ 冷启动池 |
|---|---|---|---|
| 模式 | pull (Job 主动拉) | push (写扩散到关注者) | push (写到自己性别池) |
| 写触发 | 每 5 分钟 | 发帖 `@Async` | 发帖同步 |
| 数据规模 | 全网 × 3 天,Top 3000/性别 | per-user × 100 条 | 全网 × 7 天 ≤ 10000/性别 |
| 排序 | Hacker News 热度分 | epoch 倒序 | epoch 倒序 |
| 性别分桶 | ✅ 按发帖人性别 | ❌ per-user 私有 | ✅ 按发帖人性别 |
| 写失败后果 | 池陈旧,读降级 | 该用户少一条好友帖,5 分钟后 ① 兜底 | 该新帖少一路曝光,仍可能进 ① |
| 读侧用途 | 主力填充 8/10 位置 | 第 3 位强插 | 第 6 位扶持 |
| 与 Hacker News 算法关系 | **唯一**用打分公式的 | 不打分 | 不打分 |

### 10.3 三路混合 + 强插

10 条一页的位置分配规则:

| 位置 | 优先取 | 一级降级 | 二级降级 |
|---|---|---|---|
| 1, 2, 4, 5, 7, 8, 9, 10 | recommend(热门池)| cold_start(冷启动池)| friend(好友池)|
| 3 | friend(好友强插)| recommend | — |
| 6 | cold_start(新帖扶持)| recommend | — |

**频控**:同一好友单次最多 1 条(`usedFriendUserIds` Set 维护),防 1 个话痨好友刷屏。

**布隆去重**:误判率 1%,最多损失 1% 可见内容,体验无感。7 天 TTL 过期后用户可重新看到老帖,符合预期。

### 10.4 兜底场景

| 场景 | 行为 |
|---|---|
| 用户没好友 / 好友全没发帖 | 第 3 位降级 recommend |
| recommend 池空(刚起服 + 5 分钟没到)| 全部降级 cold_start;cold_start 也空就返空,App 显示「暂无内容」 |
| 某 post_id 取出来时已被删 | `getPostDetail` 抛 BizException,catch warn 跳过(该页可能不足 10 条) |
| Bloom 命中率过高(用户重度刷)| TTL 7 天到期自动重置;期间用户偶尔重看老帖,可接受 |

---

## 11. 跨服务依赖(UserClient)

本服务**唯一**外部 gRPC 依赖:`user-service`,通过 Nacos `discovery:///user-service` 解析。

```java
@Component
public class UserClient {
    @GrpcClient("user-service")
    private UserServiceGrpc.UserServiceBlockingStub stub;

    // ① 取好友 user_id 列表,用于发帖写扩散
    public List<Long> getFriendUserIds(Long userId) { ... }

    // ② 取性别,用于 Feed 池分桶
    //    返回:true=男 / false=女 / fallback false(user-service 不可用 / 用户未设置)
    public boolean isMale(Long userId) { ... }

    // ③ 批量取性别,FeedScoreJob 重建池时用
    public Map<Long, Boolean> getGenders(List<Long> userIds) { ... }
}
```

**关键约束**:

1. **本服务不缓存 user-service 返回的资料到 Redis**(`user-service` 自己已有 `user:profile:*` 缓存,二级缓存只会让一致性更难)。
2. **但允许本地短 TTL Caffeine**(30 秒):`isMale` / `getGenders` 在 `FeedScoreJob` 重建池时会被 3000+ 次调用,RPC 风暴用 Caffeine 削峰。Caffeine 是进程内,挂了也不影响数据一致性。
3. **user-service 不可用降级**:`getFriendUserIds` 返空(写扩散 no-op,用户只走全网池);`isMale` 默认 false(归到女性池,可接受不准)。绝不因 user-service 抖动让发帖 / Feed 失败。

> 如果你的 `user-service` 当前没实现「好友列表」「性别字段」,本服务**先用桩实现**(getFriendUserIds 返空、isMale 用 `userId % 2 == 0` 测试用),等 `user-service` 实现后无缝替换 stub 即可。

---

## 12. 一致性 / 并发 / 幂等

| 场景 | 风险 | 解法 |
|---|---|---|
| 多端并发点赞同一帖 | post_likes 重复 INSERT 冲突 | PK `(user_id, post_id)` + `ON CONFLICT DO UPDATE`,原子幂等 |
| 多实例 LikeFlushJob 同时跑 | post_stats 重复加 / 漏加 | Lua「GET + SET 0」原子 + `UPDATE += delta` + ShedLock 互斥 |
| 写扩散到 5000 关注者中途崩 | 部分人 timeline 缺这帖 | 容忍降级:5 分钟池重建后可从热门池看到 |
| 评论 ZSet 没裁剪到 200 | Redis 内存膨胀 | 每次 ZADD 立即 ZREMRANGEBYRANK |
| 删帖后 timeline / 池里残留 | 读到 404 | `getPostDetail` 抛 BizException,FeedService try-catch 跳过 |
| user-service RPC 失败 | 写扩散 / 性别分桶失败 | UserClient fallback;不让发帖 / Feed 失败 |

**事务红线**(`student-dev-guide §7.4` / 红线 9):
- `@Transactional` 只在 service 方法,不嵌套远程调用。
- 跨服务**绝不分布式事务**;写扩散 / 计数刷盘都是异步 best-effort。

---

## 13. 失败模式 / 降级

| 故障 | 影响 | 降级 |
|---|---|---|
| Redis 全挂 | 计数读不准、缓存全 miss、Feed 全空 | DB 仍可写;前端能发帖能看自己帖子;Feed 返空 → App 引导「关注好友」|
| PG 主库挂 | 写全失败;读靠 7 天的 `post:detail:*` 兜底 | 健康检查失败,gateway 熔断本服务 |
| user-service down | 写扩散 no-op;FeedScoreJob 全部归一性别池 | 降级为「无性别区分」推荐,体验下降 |
| `LikeFlushJob` 卡死 30 分钟 | 增量在 Redis 累加 | 监控 `<name>:post:updated_set` 长度报警,恢复后一次性消化 |
| MinIO 故障 | 图片 404 | 帖子文本仍可见;前端 UI 兜底占位图 |
| `FeedScoreJob` 卡住 | 池数据陈旧(>5 min)| 短期可接受;若长时间不重建,只看到 cold_start + friend |

---

## 14. 监控 & 可观测

**日志**(`student-dev-guide §7.5 / §9.2`):
- SLF4J 打 stdout,**禁写文件**;本机 Promtail → Loki,Grafana `http://localhost:3000` 查询。
- ERROR 必带堆栈:`log.error("xxx failed, postId={}", postId, e)`。
- 关键链路日志带 `traceId` / `userId`(MDC 透传)。
- 关键 keyword(grep 友好):
  - `Post created: postId={} userId={} images={}`
  - `Like action: userId={} postId={} liked={}`
  - `Fanout complete: postId={} followers={}`
  - `Feed returned: userId={} size={} recommend={} friends={} coldStart={}`
  - `Like flush completed, processed={}`
  - `Feed pool rebuilt, candidates={} male={} female={}`

**指标**(本机暂不强求,接 Prometheus 后):

| 指标 | 类型 | 含义 |
|---|---|---|
| `post.create.{success,fail}` | Counter | 发帖成功率 |
| `post.like.action` | Counter(tag: action=LIKE/UNLIKE) | 点赞行为分布 |
| `post.like.flush.batch_size` | Histogram | 每次刷盘批量 |
| `feed.recommend.duration` | Timer | Feed 读耗时 |
| `feed.recommend.bloom.hit` | Counter | 被布隆过滤掉的 |
| `feed.score.rebuild.duration` | Timer | 重建池耗时 |
| `post.updated_set.size` | Gauge | 待刷盘队列长度(报警阈值 1 万) |
| `user_client.rpc.fail` | Counter(tag: method) | 跨服务调用失败 |

---

## 15. 安全 / 鉴权 / 风控

**鉴权**:全部依赖 `mobile-gateway` 注入 user_id,本服务内部信任。**绝不在 post-service 解 JWT**。

**权限校验**:
- 删帖 / 删评论 → 操作人必须 == 资源 owner。
- 其他操作目前无 ACL(任何登录用户可看 / 点赞 / 评论任何公开帖)。

**风控**(本期不做,留扩展点):
- 发帖频控:service 入口 Redis `INCR + EXPIRE` 限 `user_id`(例:1 分钟 3 条)。
- 评论敏感词:独立 `moderation-service` 或外部审核 SDK。
- 黑名单 / 拉黑:由 `user-service` 统一管;`FeedService.mergeThreeWay` 加过滤 hook。
- 图片 NSFW:异步任务上传后调审核服务,违规 `posts.status = 2`。

---

## 16. 实施步骤(学员落地清单)

按 `student-dev-guide §3 / §9` 走,顺序如下:

### Day 1 ~ 2:基础

- [ ] 在自己 `<yourpinyin>-workspace` 仓库下,复制 `dating-server/example-service` → `post-service`,改包名 `com.dating.post`(`student-dev-guide §A`)
- [ ] `proto/post/post.proto` 编 9 个 RPC + 5 个 message;`mvn deploy` 推到 Nexus(`com.dating.<name>.proto:post-proto:0.1.0`)
- [ ] `post-service/pom.xml` 加依赖:
  - `com.dating.<name>.proto:post-proto:0.1.0`
  - `grpc-server-spring-boot-starter:3.1.0.RELEASE` + `grpc-netty-shaded:1.68.1`
  - `mybatis-plus-spring-boot3-starter:3.5.9` + `postgresql` + `flyway-core` + `flyway-database-postgresql`
  - `redisson-spring-boot-starter`
  - `dating-common`(对象存储)
  - `shedlock-spring` + `shedlock-provider-jdbc-template`
  - `caffeine`(UserClient 本地缓存)

### Day 3:数据层

- [ ] Flyway V20260615_01 SQL:5 张表 + 索引 + `shedlock` 表
- [ ] 在 Nacos `dev-<name>` namespace 配 `post-service-dev.yaml`,Redis/PG/MinIO/Snowflake worker-id 都从这里读
- [ ] 5 个 Entity + 5 个 Mapper(Mapper XML 只放 `post_likes` 的 upsert)

### Day 4 ~ 5:核心业务

- [ ] Manager(单表读写 + Redis 操作)
- [ ] Service:`PostWriteService` / `PostReadService` / `LikeService` / `CommentService`
- [ ] `BizException` + `ErrorCode` + `GlobalExceptionHandler`
- [ ] 跑通本机:Postman 测 REST 增 / 查 / 删 / 点赞 / 评论

### Day 6:gRPC + Feed

- [ ] `PostGrpcService` 实现 9 个 RPC
- [ ] `UserClient`(先用桩,getFriendUserIds 返空、isMale 取模)
- [ ] `PostFanoutService`(`@Async`,在 `PostApplication` 加 `@EnableAsync`)
- [ ] `FeedService.rebuildRecommendPool` + `getRecommendFeed` + `mergeThreeWay`
- [ ] `LikeFlushJob` / `CommentFlushJob` / `FeedScoreJob`(`@EnableScheduling`)
- [ ] 用 `grpcurl` 测 gRPC:发帖 → 点赞 → 评论 → 刷盘 → Feed

### Day 7:接入 + 部署

- [ ] 在你的 `mobile-gateway` 加 `/post/*` 路由,JWT → user_id 注入 gRPC Metadata
- [ ] 写 `Dockerfile`(多阶段 `maven:3.9-temurin-21` → `temurin:21-jre-alpine`)
- [ ] 加到 `deploy/docker-compose.dev.yml`,容器名 `<name>-post-service-dev`,网络 `dating-app`
- [ ] 本机 Jenkins 流水线追加 `post-service` choice,push 后手动 Build
- [ ] 浏览器开 `http://localhost:3000` Grafana 看 Loki 日志确认启动正常

### Day 8+:迭代

- [ ] 集成测试:Testcontainers 起真 PG + Redis 跑端到端
- [ ] `user-service` 实现 `GetFriendList` / `GetProfile.gender` 后替换 UserClient 桩
- [ ] 接 Actuator + Prometheus 暴指标
- [ ] 阶段三:楼中楼 / 转发 / 收藏 / 推送(走 `im-service` 系统消息)

---

## 17. 红线 self-check(对照 `student-dev-guide §10`)

| # | 红线 | 本设计如何遵守 |
|---|---|---|
| 1 | 多表 JOIN | 所有 Mapper 单表;评论列表 / Feed 详情靠 service 多次单表 + `selectBatchIds` |
| 2 | 跨服务直连别人库 / Redis / 桶 | 好友 / 性别走 `UserClient` gRPC;workspace 共享桶但**只写自己的 `post-image/` 前缀,不读别的服务的 key 前缀** |
| 3 | 服务间 HTTP 互调 | post-service 与 user-service 全 gRPC,`discovery:///user-service` 寻址 |
| 4 | 凭据进 git | `application*.yml` 全部 `${ENV}` 占位,真值进 Nacos / `.env` |
| 5 | 引入清单外中间件 | 只用 PG + Redis + MinIO;**不引入 MQ** |
| 6 | 自建 WebSocket / 直调 OpenIM | 本期不产生 IM 消息;阶段三推送走 `im-service` |
| 7 | 生产用公网 IP 访问 PG/Redis/Nacos | 本机 dev 用 `38.76.188.242` 是规范;若部署到生产侧自然切容器名 |
| 8 | `TIMESTAMP` 或写死 `Asia/Shanghai` | 全 `TIMESTAMPTZ`,Hikari `connection-init-sql: SET TIME ZONE 'UTC'`,容器 `TZ=UTC` |
| 9 | 跨服务事务 | `@Transactional` 内不嵌远程调用;写扩散 `@Async` 在事务外 |
| 10 | controller 直调 mapper | 严格 `controller → service → manager → mapper` 单向 |
| 11 | Redis 当数据库 | 所有状态都能从 PG 重建(底座 + 增量模型;评论列表回源 DB) |
| 12 | 对外暴露内部 `id` | 所有 RPC / VO 用业务主键 `post_id` / `comment_id` |

---

## 18. 附录:核心 SQL 速查

**发帖事务**(伪 SQL,实际 MyBatis-Plus 三次单表 INSERT):

```sql
BEGIN;
INSERT INTO posts (post_id, user_id, content, status, deleted) VALUES (?, ?, ?, 1, 0);
INSERT INTO post_images (post_id, sort_order, image_key) VALUES
  (?, 0, ?), (?, 1, ?), ...;
INSERT INTO post_stats (post_id, like_count, comment_count) VALUES (?, 0, 0);
COMMIT;
```

**点赞 upsert**(`post_likes` Mapper XML):

```sql
INSERT INTO post_likes (user_id, post_id, status, created_at, updated_at)
VALUES (#{userId}, #{postId}, #{status}, NOW(), NOW())
ON CONFLICT (user_id, post_id)
DO UPDATE SET status = EXCLUDED.status, updated_at = NOW()
WHERE post_likes.status <> EXCLUDED.status;
```

**点赞刷盘**:

```sql
UPDATE post_stats SET like_count = like_count + ?, updated_at = NOW()
WHERE post_id = ?;
```

**「我的动态」分页(游标)**:

```sql
SELECT * FROM posts
WHERE user_id = ? AND deleted = 0 AND status = 1
  AND post_id < ?      -- 游标(雪花 ID 单调递增,可代时间游标)
ORDER BY post_id DESC
LIMIT ?;
```

**评论冷路回源**:

```sql
SELECT * FROM post_comments
WHERE post_id = ? AND root_id = 0 AND deleted = 0
  AND comment_id < ?
ORDER BY created_at DESC
LIMIT ?;
```

**Feed 池重建数据源**:

```sql
-- 一次性捞近 3 天所有正常帖,内存里打分 + 性别分桶
SELECT p.post_id, p.user_id, p.created_at, s.like_count, s.comment_count
FROM posts p
LEFT JOIN post_stats s ON s.post_id = p.post_id     -- ⚠️ 实际禁 JOIN!
WHERE p.deleted = 0 AND p.status = 1 AND p.created_at >= NOW() - INTERVAL '3 days';
```

> ⚠️ 上面写 JOIN 是为了直观;**实际实现必须**:
> 1. 先 SELECT `posts` 拿 3 天内的 post_id 列表
> 2. `postStatsMapper.selectBatchIds(postIds)` 批量拿计数
> 3. service 层内存 join 后再打分
> 
> 这才符合「单表 Mapper + service 拼装」红线 1。
