# user-service 技术方案

> 状态:草案 v0.3
> 范围:仅覆盖技术选型与服务设计;部署细节遵循 `docs/deploy.md`,编码规范遵循 `CLAUDE.md`,鉴权侧设计见 `docs/mobile-gateway-design.md`。
> 关联:建表脚本(含每字段 COMMENT)见 `docs/sql/user-service.sql`;运行时迁移见 `user-service/src/main/resources/db/migration/V1__init_schema.sql`(与前者保持同步)。

## 1. 背景与定位

**定位:用户身份解析 + 用户资料的领域服务**。承担用户的「我是谁」「我长什么样」「我喜欢什么」的全部持久化能力。

服务名:**`user-service`**。

**数据模型**(V1 共 5 张表):

| 表 | 用途 |
|---|---|
| `user_info`                       | 主资料(含 last_open_at) |
| `user_login_phone`                | 手机号 ↔ userId 绑定 |
| `user_third_party_registration`   | 第三方账号 ↔ userId 绑定 |
| `user_device_registration`        | 设备 ↔ userId 绑定(快速登录) |
| `user_interest`                   | 兴趣标签 |

与 `mobile-gateway` 的职责分割(关键、必须先看):

| 维度 | mobile-gateway(鉴权域) | user-service(身份/资料域) |
|---|---|---|
| 关注问题 | 「这一次会话」凭证 | 「我是谁」「我长什么样」 |
| 表 | `auth_device`(设备指纹 / 推送 token)/ `auth_refresh_token`(refresh token hash) | `user_info` / `user_login_phone` / `user_third_party_registration` / `user_device_registration` / `user_interest` |
| 输入 | 短信验证码 / 第三方 token / 设备 ID | 已验证过的 phone / third-party id / device id |
| 输出 | access JWT + refresh token | userId + 用户资料 |
| 是否调对方 | 调 user-service `ResolveOrCreate` 拿 userId | 不调网关 |

登录闭环示意:

```
[App] ──phone+sms──▶ [gateway]
                        │ 1. Redis 验短信码
                        │ 2. gRPC user-service.ResolveOrCreateByPhone(phone, appName)
                        │ 3. 检查封禁状态(gRPC user-service.CheckBan)
                        │ 4. 本地签 JWT + refresh token(写 PG auth_*)
                        ▼
                  {access_token, refresh_token, userId}
```

## 2. 架构

```
                  [mobile-gateway]
                        │ gRPC (Nacos discovery)
                        ▼
        ┌────────────────────────────────────────────┐
        │   user-service                         │
        │  (Spring Boot 3.3.5 / MVC + Loom / gRPC)    │
        │  - 身份解析 ResolveOrCreate                  │
        │  - 资料读写 / 兴趣读写                       │
        │  - 头像 presign / confirm(对象存储直传)      │
        │  - 封禁查询                                  │
        └──────┬──────────────────────┬──────────────┘
               │                      │
               │                      │ 对象存储 presigned PUT
               ▼                      ▼
        PostgreSQL (5 张表)       对象存储 (bucket: dating-user)
               │
               ▼
        Redis (前缀 user:*) —— 资料 / 兴趣 / 注册锁 / 封禁
```

- 不暴露 REST(除 Actuator);对外能力一律 gRPC,调用方现阶段只有 `mobile-gateway`,未来会接 `relation-service` / `im-service` 等。
- 不写本地盘、不读文件流。头像图片走对象存储 presigned PUT 直传,user-service 只签 URL 和落库 `object_key`。
- 不持有 Kafka / RocketMQ 等 MQ。

## 3. 技术选型

| 类别 | 选型 | 说明 |
|---|---|---|
| 语言 / 运行时 | JDK 21 + `temurin:21-jre-alpine` | 与 `example-service` / 网关一致 |
| Web 框架 | Spring Boot 3.3.5 + **Spring MVC + 虚拟线程**(`spring.threads.virtual.enabled=true`) | 服务自身的 Actuator / 健康检查跑虚拟线程;gRPC 服务端线程模型独立 |
| 持久层 | PostgreSQL + **MyBatis-Plus 3.5.7** + Flyway 10 | 单表 CRUD 走 BaseMapper / LambdaQueryWrapper,复杂单表 SQL 走 XML,跨表组装在 service 层 |
| 对象存储 | S3 兼容(全环境 iDrive® e2,bucket 区分 dev/test/prod),bucket `dating-user` | 头像 / 兴趣图全部 presign 直传;DB 只存 `object_key`;统一经 `dating-common` 的 `ObjectStorage` 接口访问 |
| 缓存 | Redis(前缀 **`user:`**) | 资料 Hash + 兴趣 + 注册锁 + 封禁状态 |
| 服务发现 / 配置 | Nacos Discovery + Nacos Config | 已通过 `spring.config.import: optional:nacos:...` 接入,命名空间 `dating-test` |
| gRPC 服务端 | `grpc-java` + `grpc-spring-boot-starter` | 待添加依赖;端口 `9090`(区别于 HTTP `8080`) |
| Proto 依赖 | Nexus Maven 仓库 `com.jianjiange.proto:user-proto:<version>` | 三语言同版本号;不在本仓库跑 protoc(红线 4) |
| 对象映射 | **MapStruct(仅 POJO 侧) + 手写 proto builder** | entity↔VO、proto getter→VO 用 MapStruct;出参 proto message 一律手写 builder(MapStruct 不能 target protobuf builder,引第三方扩展踩红线 6);禁止 BeanUtils |
| 参数校验 | `spring-boot-starter-validation` (JSR-380) | `@Valid` + 全局异常 |
| 手机号规范化 | `libphonenumber` | 入参一律 normalize 到 E.164 |
| 分布式锁 | Redisson | 注册解析锁 `lock:user:register:<key>` |
| 健康检查 / 指标 | Actuator + Micrometer Prometheus | 已暴露 `/actuator/health,info,prometheus` |
| 链路追踪 | Micrometer Tracing + W3C TraceContext | gRPC `ServerInterceptor` 从 Metadata 读 `x-trace-id` 注入 MDC |

**当前 pom 还缺**(开工时补齐):
- `grpc-spring-boot-starter` + proto stub 依赖
- `redisson-spring-boot-starter`
- `dating-common`(已传递 `software.amazon.awssdk:s3` + `ObjectStorageAutoConfiguration`,业务直接 `@Autowired ObjectStorage`)
- `mapstruct` + processor
- `libphonenumber`
- `micrometer-registry-prometheus` + `micrometer-tracing-bridge-otel`

## 4. 包结构

严格遵循 `CLAUDE.md` 的服务内部分层。当前已有 `api/` + `config/`,开工时按下面结构扩展(`api/` 重命名为 `controller/`,或保留仅放 Actuator 类小接口):

```
com.dating.user
├── UserInfoApplication
├── grpc/                # gRPC 服务端实现,只编排 service
│   ├── UserIdentityGrpcImpl      # ResolveOrCreate / CheckBan
│   ├── UserProfileGrpcImpl       # Profile + Interest + Avatar 全包
│   └── interceptor/              # ServerInterceptor:Metadata 取 userId/traceId 注入 MDC
├── controller/          # 仅 Actuator
├── service/             # 业务编排;事务边界
│   └── impl/
│       ├── UserIdentityServiceImpl   # ResolveOrCreate 主算法
│       ├── UserProfileServiceImpl    # Get/Update/Onboarding + 缓存
│       ├── UserAvatarServiceImpl     # presign / confirm
│       ├── UserInterestServiceImpl
│       └── UserBanServiceImpl
├── manager/             # 单表/单聚合编排,含 Redis 读写
│   ├── UserInfoManager
│   ├── UserLoginPhoneManager
│   ├── UserThirdPartyManager
│   ├── UserDeviceManager
│   └── UserInterestManager
├── mapper/              # MyBatis-Plus Mapper(一个 Mapper 一张表,共 5 个)
├── entity/              # 与 V1 的 5 张表 1:1
├── dto/                 # 入参(含 gRPC 请求转换中间对象)
├── vo/                  # 出参
├── converter/           # MapStruct entity ↔ proto / entity ↔ vo
├── client/              # 调用其他服务的 gRPC stub 封装(开工时按需新增)
├── config/              # @Configuration(已有 MybatisPlusConfig / MetaObjectHandler)
├── constant/            # 枚举 + 常量(AppName / Platform / RegulationStatus / 缓存 key 前缀)
└── exception/           # BizException + 全局异常处理 + gRPC StatusException 转换
```

**调用方向**严格单向:`grpc → service → manager → mapper`。

- 禁止 grpc 直接调 mapper;禁止跨服务直连别人家的库(红线 2)。
- 没有 `controller` 也行,但 Actuator 之外不允许对外 REST(CLAUDE.md:对外才走 REST,本服务是内部 gRPC 服务)。

## 5. 关键设计

### 5.1 与 mobile-gateway 的鉴权域边界

见 §1 表格,再补三条规则:

1. **网关不查 user-service 数据库**,所有读写经 gRPC。
2. **user-service 不签发 / 不验证 JWT**,gRPC 调用方的 `userId` 通过 Metadata `x-user-id` 传入,由 `UserContextServerInterceptor` 读出后注入 gRPC `Context` + MDC(service 层经 `UserContext.callerUserId()` 读取);`x-device-id` / `x-trace-id` 一并透传,trace 缺失时生成 UUID。`app_name` 与目标 `user_id` 走 proto 请求体,不进 metadata。
3. **无密码登录**:本产品只支持「手机验证码 / 三方授权 / 设备 ID 快速登录」三种方式,不存在密码登录;`user_info.phone_number` / `email` 是「联系方式」语义,不作为登录凭证。

### 5.2 gRPC 接口清单

按领域切两个 service proto(具体 proto 在 [proto 仓库](https://gitee.com/jianjiange-site/proto) 维护,本服务只消费 stub):

**`UserIdentityService`**(身份解析 + 封禁)

| RPC | 用途 | 入参关键字段 |
|---|---|---|
| `ResolveOrCreateByPhone`     | 网关短信登录后调用:找现有用户或创建 placeholder,并更新 `last_open_at` | `phoneE164, appName` |
| `ResolveOrCreateByThirdParty`| 第三方登录后调用,并更新 `last_open_at` | `platform, thirdPartyUserId, appName, googleEmail?` |
| `ResolveOrCreateByDevice`    | 快速登录(无短信 / 无三方):用 deviceId 找现有用户或创建 placeholder,并更新 `last_open_at` | `deviceId, platform, appName` |
| `CheckBan`                   | 网关 / 业务方查封禁 | `userId` → `BanResult` |

**`UserProfileService`**(资料 + 兴趣 + 头像,合一)

| RPC | 用途 |
|---|---|
| `GetProfile`            | 单用户读取,**默认返回主资料 + 兴趣 + 头像 URL**(不裁剪由网关 BFF 决定字段) |
| `BatchGetProfile`       | 批量读取(≤200 / 次),Redis miss 后批量 DB 回填 |
| `UpdateProfile`         | 资料编辑(动态 SET,跳过 null 字段);**MVP 暴露字段见下表** |
| `UpsertOnboarding`      | onboarding 一次性写入完整资料 + 默认头像;**`gender` / `birthday` 等编辑页改不到的字段的唯一写入入口** |
| `ReplaceUserInterests`  | 全量替换兴趣标签;图片标签 ≤ 9,文字标签 ≤ 50 |
| `PresignAvatarUpload`   | 签 putObject URL,object_key = `avatar/{userId}/{uuid}.{ext}` |
| `ConfirmAvatarUpload`   | 客户端上传完调用,校验存在 + 更新 `user_info.custom_avatar` JSONB + 清缓存 |

**`UpdateProfile` MVP 字段集**(对齐 App「Edit info」编辑页):

| UI 字段 | proto / VO 字段 | entity / DB 字段 | 备注 |
|---|---|---|---|
| Avatar     | 不在 UpdateProfile | `user_info.custom_avatar` JSONB | 走 `PresignAvatarUpload` + `ConfirmAvatarUpload` |
| Tags       | 不在 UpdateProfile | `user_interest` 全表 | 走 `ReplaceUserInterests` |
| Age        | `age`         | `user_info.age`            | SMALLINT |
| Nickname   | `nickname`    | `user_info.nickname`       | 长度 ≤ 64,前后去空格 |
| Location   | `location`    | `user_info.preferred_location` | 截图显示「北京市」,存中文字符串,长度 ≤ 128 |
| Bio        | `bio`         | `user_info.bio`            | 长度 ≤ 500(service 层卡) |
| Occupation | `occupation`  | `user_info.profession`     | **UI 叫 Occupation,DB 叫 profession** —— MapStruct converter 做映射,proto / VO 一律 `occupation` 对齐前端 |
| Education  | `education`   | `user_info.education`      | 长度 ≤ 128 |
| Height     | `height`      | `user_info.height`         | SMALLINT,单位 cm |

**`UpdateProfile` 接口面规则**:

1. `UpdateProfile` proto **只声明上表 7 个标量字段**(avatar / tags 走专用 RPC);schema 里其余 sitin 继承字段(`gender` / `birthday` / `email` / `ins_*` / `cai_user_type` / `locale` / `platform` / `condition` 等)**不出现在 proto 里**,避免给前端可写入路径。
2. 注册/onboarding 期的字段(`gender` / `birthday` 等)通过 `UpsertOnboarding` 一次性写入;产品要求这些字段定后不可改,所以业务上不暴露 update 接口。
3. 联系方式:`phone_number` 改绑、`email` 绑定 MVP 都不暴露 RPC,等账户页需求明确再说。
4. **Schema 字段先全部保留**(不立刻 V2 精简),但 entity 仅暴露 MVP 用到的列;后续产品确认永久不用的字段,再 PR 起 `V2__drop_legacy_columns.sql`。

### 5.3 身份解析(ResolveOrCreate)

phone / third-party 两套显式入口,**不混合多键优先级**。

**`ResolveOrCreateByPhone(phoneE164, appName)`**:

```
1. libphonenumber 校验 + 规范化
2. Redisson 加锁 lock:user:register:phone:<phoneE164>:<appName>,TTL 30s
3. UserLoginPhoneManager.findByPhoneAndApp(phoneE164, appName)
     命中 → UserInfoManager.touchLastOpenAt(userId) → 返回 userId
     未命中:
       a. UserInfoManager.insertPlaceholder(appName)  // INSERT RETURNING id
       b. UserLoginPhoneManager.insert(userId, phoneE164, appName, verifiedAt=now)
       c. 返回 userId(pending=true)
4. 解锁
```

**`ResolveOrCreateByThirdParty(platform, thirdPartyId, appName, googleEmail?)`**:

```
1. Redisson 加锁 lock:user:register:tp:<platform>:<thirdPartyId>
2. UserThirdPartyManager.findActive(platform, thirdPartyId)
     命中 → touchLastOpenAt → 返回 userId
     未命中:插入 user_info placeholder + insert third_party 绑定
3. 解锁
```

**`ResolveOrCreateByDevice(deviceId, platform, appName)`**(快速登录):

```
1. Redisson 加锁 lock:user:register:dev:<platform>:<deviceId>:<appName>,TTL 30s
2. UserDeviceManager.findActive(deviceId, platform, appName)
     命中 → UserInfoManager.touchLastOpenAt(userId) → 返回 userId(pending 由 user_info.pending 决定)
     未命中:
       a. UserInfoManager.insertPlaceholder(appName)
       b. UserDeviceManager.insert(userId, deviceId, platform, appName)
       c. 返回 userId(pending=true)
3. 解锁
```

> **快速登录用户的「升级」**:绑定手机号 / 三方账号是在 onboarding 或账户页另起 RPC(MVP 不暴露,待 §9 升级 UX 决策),不在 ResolveOrCreateByDevice 路径里做。同一 userId 可同时持有 phone / third-party / device 三种绑定。

**placeholder 字段**:`pending=true, nickname='User_${id}', gender=0, regulation_status=0`,由 onboarding 流程补齐。

### 5.4 头像上传

对象存储 presigned PUT 直传(经 `dating-common` 的 `ObjectStorage`),绕开服务端转发流量。

```
[App] --1. POST /presign {ext}--> [gateway] --gRPC--> [user-service]
                                                          │ 生成 objectKey =
                                                          │   "avatar/{userId}/{uuid}.{ext}"
                                                          │ objectStorage.presignedPutUrl(objectKey, Duration.ofMinutes(5))
                                                          ▼
                                                {presignedUrl, objectKey}

[App] --2. PUT presignedUrl + 文件二进制--> [对象存储(iDrive e2)]

[App] --3. POST /confirm {objectKey}--> [gateway] --gRPC--> [user-service]
                                                          │ objectStorage.headObjectSize / doesObjectExist 校验存在
                                                          │ UserInfoManager.updateCustomAvatar(userId,
                                                          │     {originalKey: objectKey, minKey: ?, midKey: ?, ...})
                                                          │   note: minKey / midKey 缩略图生成留给后续 worker;
                                                          │         MVP 阶段三档都填同一个 originalKey,
                                                          │         展示侧降级到原图。
                                                          │ evict user:profile:big:{userId}
                                                          ▼
                                                          {ok}
```

**头像下行(VO/gRPC 契约)**:

- bucket 走 **CDN public** 模式(头像非敏感,公开缓存性价比最高),iDrive e2 公网端点 + Cloudflare 边缘缓存,dev/test/prod 各自 bucket 隔离。
- **服务端不签 URL**:`GetProfile` / `BatchGetProfile` / `ConfirmAvatarUpload` 在 VO/proto 里只回 `originalKey` / `minKey` / `midKey`(及兴趣的 `picKey`),由 App 侧自拼 `${cdnBaseUrl}/${bucket}/${key}`。cdnBaseUrl 由 App 配置中心下发,服务端零参与。
- 理由:头像无须临时凭证,反盗刷上 CF Token-based hotlink protection 即可;让 App 自拼 URL 省掉服务端为每次 GetProfile 调 `publicUrl(...)` 拼字符串的开销,也避免重 endpoint 变更要逐服务改实现。
- 如果未来某些隐私资产需要私有 bucket,再走 `presignedGetUrl` 单独通道,不污染头像/兴趣 VO 契约。

> **proto 字段命名跟进**:user-proto 0.2.x stub 的 `Avatar.{original,min,mid}_url` / `UserInterest.pic_url` 历史命名沿用,字段值已切到承载 `object_key`;下一版 0.3.0 配合破坏性版本号同步发布时,把这些字段重命名为 `*_key` 三语言对齐(列入 §9 待决策)。

**关键约束**(来自 CLAUDE.md 对象存储规范 + 红线):

- 入库只存 object_key,不存完整 URL。
- 大小 / 类型校验在 `service` 层(presign 前校验扩展名白名单 `{jpg,jpeg,png,webp}`,大小通过 presign 的 `Content-Length` 头限制 ≤ 10MB)。
- bucket 写入只对 user-service 服务开放(独立 access key);App 读走 CDN public URL,不裸暴露对象存储 API 端口。
- presigned PUT URL TTL 5 分钟,超时 App 重新调 presign。

### 5.5 资料读写与缓存

**缓存 key 规范**(CLAUDE.md:`<service>:<domain>:<id>`,本服务全部 `user:*`):

| Key | 类型 | TTL | 说明 |
|---|---|---|---|
| `user:profile:{userId}` | Hash | 24h | user_info 主表字段镜像(不含 custom_avatar 等大字段,避免热 key) |
| `user:profile:big:{userId}` | String(JSON) | 24h | custom_avatar 等大字段独立 key |
| `user:interest:{userId}` | String(JSON) | 7d | 兴趣全量 JSON |
| `user:ban:status:{userId}` | String | 5m | 封禁状态短缓存,避免登录高频回源 |
| `lock:user:register:phone:<...>` | String(NX) | 30s | 注册解析锁 |
| `lock:user:register:tp:<...>` | String(NX) | 30s | 注册解析锁 |

**一致性策略**(CLAUDE.md 强约束):

- 一律 **「先写库,再删缓存」** cache aside,禁止双写。
- 批量读:`BatchGetProfile` 走 `MGET` Hash → 集合 miss 的 ID → 一次性 `SELECT ... WHERE id IN (...)` 回填 → `pipelined HMSET`。禁止 N+1。
- 主表大字段(custom_avatar JSONB 等)独立 key,避免 Hash 单 field 几 KB 影响其他字段读取。

### 5.6 兴趣标签

- 全量替换语义:`ReplaceUserInterests` = 事务内 `DELETE WHERE user_id=? + INSERT 批量`,再 `DEL user:interest:{userId}`。
- 图片标签 `pic_key` 是对象存储 object_key(运营预上传 / 用户上传都进同一逻辑);**服务端不签 URL**,`GetProfile` 透传 `picKey`,App 侧自拼 `${cdnBaseUrl}/${bucket}/${pic_key}`(与头像同模式)。
- 业务校验:图片 ≤ 9 / 文字 ≤ 50,在 service 层做。

### 5.7 封禁与监管

封禁来源两类:

1. **用户级**:`user_info.regulation_status IN (2, 5)`(Banned / Suspended)。
2. **运营级**:Redis Set `user:ban:thirdparty-set` —— 由运营管理后台写入(写入方未定,见 §9),由本服务 `CheckBan` RPC 读取。

`CheckBan(userId)` 返回结构化 `BanResult`:

```proto
message BanResult {
  bool   banned        = 1;
  string reason        = 2;  // USER_BANNED / USER_SUSPENDED
  int64  banned_at_ms  = 3;
  string message       = 4;  // 给用户的提示文案(多语言由网关取)
}
```

网关在登录流程的最后一步调用,命中即拒签 JWT;业务侧(im / relation 等)若收到长会话过期后的请求,也可调用做兜底校验。

**枚举映射收口**:DB 与 proto 取值不一致的枚举只在一处转换,不散落到各 service。

- `regulation_status`:DB SMALLINT 比 proto `RegulationStatus` 小 1(DB 2=Banned / 5=Suspended ↔ proto BANNED=3 / SUSPENDED=6),映射只走 `RegulationStatusMapping`,封禁判定固定 `IN (2, 5)`。
- `gender`:DB 与 proto 取值一致(0 未知 / 1 男 / 2 女),经 `GenderMapping` 做 null 防护。
- `occupation`(VO)↔ `profession`(DB):命名差异在 `UserInfoConverter` 显式 `@Mapping`。

### 5.8 错误码约定

`Result<T>{ code, message, data }` 与 mobile-gateway 对齐(见 `mobile-gateway-design.md` §5.6)。gRPC 侧 `BizException` 由 `GrpcExceptionAdvice`(`@GrpcAdvice`)转 `StatusRuntimeException`,网关 client 再还原成 `Result`。

- **通用段**:`400` 参数非法 / `401` 未认证 / `403` 封禁·权限 / `404` 不存在 / `429` 限流 / `500` 系统错误。
- **业务段分区**:user-service 独占 `10001–10499`,mobile-gateway 独占 `10500+`,互不重叠,看码即知归属哪个服务。
  - `100xx` 用户(USER_NOT_FOUND=10001 / USER_BANNED=10002 / USER_SUSPENDED=10003 / OPERATIONAL_BANNED=10004)
  - `101xx` 头像 / `102xx` 兴趣 / `103xx` 身份解析(PHONE_INVALID=10301 等) / `104xx` 批量(BATCH_SIZE_EXCEEDED=10401)
- 码值定义以 `exception/ErrorCodes.java` 为准,本表只钉段位约定。

## 6. 与现有约束的对齐

| CLAUDE.md 红线 | 本方案如何遵守 |
|---|---|
| 红线 1:持久层多表 JOIN | 4 张表全部单表 CRUD;BFF 聚合不在本服务做;批量取 `WHERE in (...)` 一次性捞 |
| 红线 2:跨服务直连别人库 | 不持有任何其他服务的库 / Redis key;自身 Redis key 全部 `user:` 前缀 |
| 红线 3:服务间 HTTP 互调 | 对外只暴露 gRPC(HTTP `8080` 仅 Actuator);调他人服务也走 gRPC |
| 红线 4:proto 拷贝 / submodule | stub 走 Nexus Maven `com.jianjiange.proto:user-proto:<ver>`,pom 声明,本仓库不跑 protoc |
| 红线 5:密钥入仓 | DB / Redis / 对象存储凭证全部 Nacos Config + 环境变量,application.yml 只有占位符 |
| 红线 6:未评审中间件 | 仅使用 PG / Redis / S3 兼容对象存储 / Nacos / gRPC,全部在白名单 |
| 红线 7:自建 IM / 长连 | 本服务不接触 IM;UserSig 由 `mobile-gateway` 调 `im-service` 获取 |

## 7. 不做什么(边界)

- ❌ 不做登录 / 不签 JWT / 不存密码 hash —— 这是 `mobile-gateway` 的事。
- ❌ 不做 BFF 聚合 / 不裁字段给前端 —— 这是 `mobile-gateway` 的事。
- ❌ 不接腾讯云 IM SDK —— UserSig / 推送在 `im-service`。
- ❌ 不接第三方 OAuth SDK —— 第三方 token 校验在 `mobile-gateway`,本服务只接收已验证的 `thirdPartyUserId`。
- ❌ 不做关系链(关注 / 拉黑) —— 在 `relation-service`。
- ❌ 不做支付 / 钱包 —— 积分账本如有需求,反向拆 `user-credit-service` + `reward_token_ledger` 单独服务(红线 6 也要走评审)。

## 8. 部署

- Dockerfile 复用 `example-service` 模板(builder `maven:3.9-temurin-21` → runtime `temurin:21-jre-alpine`),已落地。
- 加入共享 Docker 网络 `dev-ops_my-network`。
- 端口:HTTP `8080`(Actuator)+ gRPC `9090`;外部不开公网,仅内网调用。
- 测试环境端口绑定 `127.0.0.1:18083:8080`(deploy/.env.deploy.example 已分配)。
- PG / Redis / 对象存储 / Nacos 凭证从环境变量 + Nacos Config 注入,**不入仓**。
- Flyway 启动时自动迁移 `db/migration/V*.sql`,`baseline-on-migrate: true`。
- Spring Boot `server.shutdown=graceful`,配合 Nacos 下线先摘流量再退出;gRPC 服务端需配 `awaitTermination` 接收停机信号。
- 健康检查:`/actuator/health`(liveness / readiness probe 已开),docker compose 接入。
- Jenkins Pipeline 已加入 `Jenkinsfile` choices。

## 9. 后续待决策

- [ ] **手机号绑定多 App 策略**:当前 `user_login_phone` 唯一约束 `(phone_e164, app_name)`,允许同号在不同 App 各自有用户;未来如果产品改为「全局唯一手机号」,要走数据迁移。
- [ ] **缩略图生成**:MVP 阶段 `min/mid/original` 三档存同一个 object_key。后续是同步生成(写入 service 层耗时)还是异步 worker?
- [ ] **运营级封禁写入方**:当前 `CheckBan` 只读 Redis `user:ban:thirdparty-set`,写入方(运营后台 / 风控服务)未定。明确后再放写接口。
- [ ] **快速登录:device ID 不稳定问题**:iOS IDFV 卸载重装会变 / Android SSAID 工厂重置会变,变更后旧 placeholder 用户找不回,数据相当于丢。是否引入「客户端 keychain 持久化兜底 token」或强制走 onboarding 绑手机才发完整资料?
- [ ] **快速登录:反作弊**:无短信 / 无三方,刷号成本极低。是否引入 iOS DeviceCheck / App Attest + Android Play Integrity 作为 device 入口的前置校验?MVP 是否先靠 IP / 频次限流兜底?
- [ ] **快速登录 → 实账户升级 UX**:device 用户后续补绑 phone / 三方时,要新起一个 `BindPhone` / `BindThirdParty` RPC(同 userId 多绑定);若该 phone / 三方已绑别的 userId,要走「合账还是拒绝」决策。MVP 先拒绝,合账下一版再做。
- [ ] **user-proto 0.3.0 字段重命名**:`Avatar.{original,min,mid}_url` / `UserInterest.pic_url` 已经承载 object_key,值正确但命名误导;下一版破坏性版本号同步发布时改名 `*_key`,三语言对齐。改名前 user-service ProtoMapper / mobile-gateway UserProfileConverter 显式做了 *_url ↔ *Key 桥接,业务无感。

## 10. 落地清单(开工后)

1. **proto 仓库**新增 `user_info.v1` proto 包(`UserIdentityService` / `UserProfileService`),CI 跑通三语言 stub 发布到 Nexus。
2. **pom 补依赖**:grpc-spring-boot-starter / redisson / mapstruct / libphonenumber / micrometer prometheus + tracing(对象存储能力随 `dating-common` 传递,无需单独加 SDK)。
3. **包结构**:按 §4 把 `grpc/ service/ manager/ mapper/ entity/ dto/ vo/ converter/ constant/ exception/` 一次性建好(先空壳类)。
4. **gRPC 服务端启动**:端口 9090;`ServerInterceptor` 实现 traceId / userId 透传到 MDC;全局异常 → `StatusException`。
5. **Identity 域**:实现 `ResolveOrCreateByPhone` / `ResolveOrCreateByThirdParty` / `ResolveOrCreateByDevice` + Redisson 注册锁,先跑通 placeholder 注册路径;`CheckBan` 接 Redis 短缓存。
6. **Profile 域**:`GetProfile / BatchGetProfile / UpdateProfile / UpsertOnboarding`,含 Redis cache aside;`GetProfile` 解 `custom_avatar` JSONB 直接把 object_key 写进 AvatarVO,不签 URL(App 自拼)。
7. **Avatar 域**:直接 `@Autowired ObjectStorage`(来自 `dating-common`)实现 `PresignAvatarUpload` / `ConfirmAvatarUpload`。bucket `dating-user` 提前在对象存储 provider 控制台建好(prod 是 iDrive e2)。
8. **Interest 域**:`ReplaceUserInterests` 单接口(读取并入 `GetProfile`)。
9. **Nacos 配置**:DB / Redis / 对象存储凭证(`dating.storage.*`)、注册锁 TTL、presign TTL、缓存 TTL 等参数化。
10. **集成测试**:用真实 PG(CLAUDE.md 测试规范)覆盖 ResolveOrCreate / UpdateProfile / Avatar presign+confirm 三条主路径。
11. **网关接入**:mobile-gateway 添加 `UserIdentityClient` / `UserProfileClient`,登录流程串通到本服务。
