# 开发环境接入手册（共享基建 Nacos / MinIO / Redis / PostgreSQL / RocketMQ / OpenIM + 本机构建联调）

> 适用范围：日常开发时，把你本机 IDE 里跑的服务**连到团队已经搭好的一套共享开发基建**（`38.76.188.242`）上，不用自己在本机装中间件。
>
> 这份文档给你三样东西：
> 1. **代码里要填的连接配置**（PG / Redis / MinIO / Nacos / RocketMQ / OpenIM），直接复制改改就能用；
> 2. **怎么自己登录上去看数据 / 看配置**（Web 控制台 + 客户端工具）；
> 3. **怎么在本机用 Jenkins + Docker 把整套服务跑起来、看日志、和安卓包联调**（§8）。
>
> 凭据是开发环境共享凭据，**只允许填在本机 profile / Nacos 里，绝不要 commit 进任何业务 git 仓库**。
>
> 想完全离线、在自己电脑上起一套中间件自己玩，看另一份 `local-infra-setup.md`。本文是连**远端共享**那套。

---

## 0. 速查表（从你本机连）

| 组件 | 开发机连接地址 | Web 控制台 / 登录入口 | 账号 | 密码 |
|---|---|---|---|---|
| PostgreSQL | `38.76.188.242:5433` | 用DataGrip | `jianjian_test` | `MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V` |
| Redis | `38.76.188.242:6380` | 用DataGrip连 | （无用户名） | `sNuP9gZScsj88QbEyTujffOvRCCH9Kv1` |
| MinIO（对象存储） | `https://minio-api.jianjiange.site` | https://minio.jianjiange.site | `admin` | `GorLDkuOhGyK5c1RXh2gaPooXgtso/MR` |
| Nacos（配置/注册中心） | `38.76.188.242:8848` | http://38.76.188.242:8848/nacos | `nacos` | `jianjiange` |
| RocketMQ（消息队列） | NameServer `38.76.188.242:9876` | https://rocketmq.jianjiange.site | AK `rocketmq-student` | SK `5cafa390b8a42c25` |
| OpenIM（IM / 音视频） | `https://nexus-mind.chatvibe.me/api` + `wss://nexus-mind.chatvibe.me/msg_gateway` | https://openim-admin.chatvibe.me | 见 §7 | 见 §7 |

> ⚠️ MinIO 在服务器上只监听本机端口（不直接对外开端口），所以**本机代码要走上面那个 `https://minio-api.jianjiange.site` 域名**，不要去连 `38.76.188.242:18900`（连不上）。
>
> ⚠️ 这套是**多人共享**的开发环境。下面每节都有「怎么不和别人打架」的隔离建议（各用各的库 / namespace / key 前缀），开工前先看一眼。

---

## 1. 先准备一个 `application-dev.yml`

把下面这段放进你服务的 `src/main/resources/application-dev.yml`，启动时加 `-Dspring.profiles.active=dev`（或在 IDE Run Configuration 里设 `SPRING_PROFILES_ACTIVE=dev`）。**密码先直接填着跑通**，跑通后挪到 Nacos / 环境变量里（见 [§6](#6-红线必看)）。

```yaml
spring:
  application:
    name: dating-xxx-service          # 改成你的服务名
  datasource:
    url: jdbc:postgresql://38.76.188.242:5433/dating_dev_yourname?stringtype=unspecified
    username: jianjian_test
    password: MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 8
      minimum-idle: 2
      connection-init-sql: SET TIME ZONE 'UTC'   # 全系统统一 UTC
  data:
    redis:
      host: 38.76.188.242
      port: 6380
      password: sNuP9gZScsj88QbEyTujffOvRCCH9Kv1
      database: 1                       # 见 §3 的隔离建议
      timeout: 3s
  cloud:
    nacos:
      discovery:
        server-addr: 38.76.188.242:8848
        namespace: dev-yourname         # 见 §4，建议建自己的 namespace
        username: nacos
        password: jianjiange
      config:
        server-addr: 38.76.188.242:8848
        namespace: dev-yourname
        username: nacos
        password: jianjiange
        file-extension: yaml

# dating-common ObjectStorage（MinIO 走 S3 协议）
dating:
  object-storage:
    provider: s3
    endpoint: https://minio-api.jianjiange.site
    region: us-east-1                   # MinIO 用不到，占位即可
    access-key: admin
    secret-key: GorLDkuOhGyK5c1RXh2gaPooXgtso/MR
    path-style-access: true             # MinIO 必须 true
    bucket: dating-yourname             # 见 §2.3，建议用自己的 bucket
```

> 如果你的服务用 `bootstrap.yml` 拉 Nacos 配置，把上面 `spring.cloud.nacos` 那段挪到 `bootstrap.yml` 里即可，其余不变。

---

## 2. PostgreSQL

### 2.1 连接

| 场景 | 命令 / 地址 |
|---|---|
| JDBC | `jdbc:postgresql://38.76.188.242:5433/<库名>` |
| psql | `psql -h 38.76.188.242 -p 5433 -U jianjian_test -d <库名>` |
| 图形客户端 | **DataGrip**：New → Data Source → PostgreSQL，填 host `38.76.188.242`、port `5433`、user `jianjian_test`、password 见速查表 |

### 2.2 自己登录上去看数据

用 **DataGrip**（JetBrains，和 IDEA 同门；https://www.jetbrains.com/datagrip/）：

1. 左上 `+` → Data Source → **PostgreSQL**。
2. Host `38.76.188.242`，Port `5433`，User `jianjian_test`，Password 见速查表，Database 填你的库（如 `dating_dev_yourname`）。
3. 第一次会提示下载 PostgreSQL 驱动，点 Download。点 **Test Connection** 通过后 Apply。
4. 左侧展开就能看到库 / 表 / 数据，右键表 → Edit Data 改数据，或新建 Query Console 写 SQL。

命令行党：
```bash
PGPASSWORD='MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V' \
  psql -h 38.76.188.242 -p 5433 -U jianjian_test -d postgres -c '\l'   # 列所有库
```

### 2.3 多人隔离：建你自己的库

共享实例里已经有一些公用库（如 `dating_chat`、`dating_im`）。**别几个人都往同一个库的同一张表写**，会互相覆盖。建议你建一个自己的库：

```bash
PGPASSWORD='MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V' \
  psql -h 38.76.188.242 -p 5433 -U jianjian_test -d postgres \
  -c 'CREATE DATABASE dating_dev_yourname;'
```

然后把 `application-dev.yml` 里的库名换成 `dating_dev_yourname`。表结构走你服务自己的建表脚本 / Flyway。

### 2.4 注意

- 时间列一律用 `TIMESTAMPTZ`，连接里已经 `SET TIME ZONE 'UTC'`，存取都按 UTC，**别在代码里写死东八区**。
- 持久层一张 Mapper 只查一张表，**不要写多表 JOIN**，跨表数据在 service 层拼。
- 别去读别的服务的库表；要别的服务的数据走它的 gRPC。

---

## 3. Redis

### 3.1 连接

| 场景 | 命令 / 地址 |
|---|---|
| redis-cli | `redis-cli -h 38.76.188.242 -p 6380 -a 'sNuP9gZScsj88QbEyTujffOvRCCH9Kv1'` |
| 连接串 | `redis://:sNuP9gZScsj88QbEyTujffOvRCCH9Kv1@38.76.188.242:6380/1` |
| 图形客户端 | **DataGrip**：New → Data Source → Redis，填 host `38.76.188.242`、port `6380`、password 见速查表 |

### 3.2 自己登录上去看

用 **DataGrip**（和看 PG 同一个工具）：

1. 左上 `+` → Data Source → **Redis**。
2. Host `38.76.188.242`，Port `6380`，Password 见速查表（无用户名）。
3. Test Connection 通过后 Apply，左侧按 db 号展开就能看到 key、TTL、内容；双击 key 看值。
4. 看哪个 db：在连接的高级设置里指定 database，或直接在 Query Console 里 `SELECT 1`（切到 db 1）再 `KEYS your-prefix:*`。

命令行党也可以：

```bash
redis-cli -h 38.76.188.242 -p 6380 -a 'sNuP9gZScsj88QbEyTujffOvRCCH9Kv1'
> select 1          # 切到你用的 db
> keys your-prefix:*
> get user:profile:1024
```

### 3.3 多人隔离 + key 规范

- **各用各的 db 号**：开发约定走 db `1`（db 0 留给别处）。人多的话每人挑一个空 db（`select 2`/`3`/…先看里面有没有 key），或者统一 db 1 但**用自己的 key 前缀**。
- key 命名：`<服务>:<领域>:<id>`，例 `user:profile:1024`。
- **必须设 TTL**，别写永久 key。
- 写库 + 缓存只能「先写库，再删缓存」，不要双写。

---

## 4. Nacos（配置中心 + 注册中心）

### 4.1 自己登录上去看配置

浏览器开 **http://38.76.188.242:8848/nacos**，账号 `nacos` / 密码 `jianjiange`。
- 「配置管理 → 配置列表」：看 / 改各服务的配置（注意右上角先选对 **命名空间**）。
- 「服务管理 → 服务列表」：看哪些服务注册上来了（你本机起的服务连上后会出现在这里）。

### 4.2 多人隔离：建你自己的 namespace

不同人/不同环境用不同 **命名空间（namespace）** 隔离，互不影响：

1. 控制台左侧「命名空间」→「新建命名空间」。
2. 命名空间名填 `dev-yourname`，ID 可以留空（自动生成）也可以手填 `dev-yourname` 好记。
3. 把生成的 **命名空间 ID** 填到 `application-dev.yml` 的 `namespace` 里（手填同名 ID 的话直接写名字即可）。

### 4.3 加一条配置

在你的 namespace 下「新建配置」：
- Data ID：`<你的服务名>-dev.yaml`（如 `dating-user-service-dev.yaml`）
- Group：`DEFAULT_GROUP`
- 格式：YAML
- 内容：把不想写在代码里的东西（数据库密码、第三方 key 等）放这里。

服务启动时会按 `spring.application.name` + profile 自动拉对应 Data ID。

### 4.4 注意

- gRPC / 服务间调用走 Nacos 服务发现，**别在代码里写死 ip:port**。
- 真实密码、token 放 Nacos，别进 git。

---

## 5. MinIO（对象存储）

### 5.1 自己登录上去看文件

浏览器开 **https://minio.jianjiange.site**，账号 `admin` / 密码见速查表。
- 「Buckets」：看 / 建桶。
- 进桶能看到对象（文件），能上传 / 下载 / 删，方便你核对程序到底写没写进去。

### 5.2 代码接入

见 [§1](#1-先准备一个-application-devyml) 的 `dating.object-storage` 段。要点：
- `endpoint` 用 **`https://minio-api.jianjiange.site`**（服务器没对外开 MinIO 端口，只能走这个域名）。
- `path-style-access: true`（MinIO 必须，否则报 `SignatureDoesNotMatch` 之类）。

### 5.3 多人隔离：建你自己的 bucket

控制台「Buckets → Create Bucket」建一个 `dating-yourname`，或命令行（装了 `mc` 的话）：

```bash
mc alias set dev https://minio-api.jianjiange.site admin 'GorLDkuOhGyK5c1RXh2gaPooXgtso/MR'
mc mb dev/dating-yourname
```

把 `application-dev.yml` 的 `bucket` 换成它。

### 5.4 对象 key 约定

- 入库 **只存 object key**，不存完整 URL。
- key 格式：`<category>/<owner_id>/<yyyymm>/<uuid>.<ext>`，临时上传放 `tmp/` 前缀。
- 出参（VO / gRPC）也只回 `*_key`，前端自己拼访问地址。

---

## 6. RocketMQ（消息队列）

> 给你的服务做**异步消息 / 削峰 / 解耦**用，需要 MQ 的功能直接接入即可。RocketMQ 5.3.1，部署在 38；公网开放 NameServer + Broker，**公网客户端必须带 AK/SK** 才能收发。

### 6.1 连接信息

| 项 | 值 |
|---|---|
| NameServer | `38.76.188.242:9876` |
| 客户端 AccessKey | `rocketmq-student` |
| 客户端 SecretKey | `5cafa390b8a42c25` |
| Dashboard | https://rocketmq.jianjiange.site |
| Dashboard 登录（Basic Auth） | `student` / `MwTt14eUL9s1M3` |

### 6.2 自己登录上去看

浏览器开 **https://rocketmq.jianjiange.site**，弹框输 `student` / `MwTt14eUL9s1M3`。进去能看到：
- **Topic**：建 topic、看 topic 列表。
- **Message**：按 topic / time / key 查消息内容，核对到底发出去没。
- **Consumer**：看消费组、消费进度、有没有堆积。

> 域名如果暂时打不开（DNS / 证书还在配），找管理员确认。

### 6.3 Spring Boot 接入

加依赖（pom.xml）：

```xml
<dependency>
  <groupId>org.apache.rocketmq</groupId>
  <artifactId>rocketmq-spring-boot-starter</artifactId>
  <version>2.3.1</version>
</dependency>
```

`application-dev.yml` 加一段（**务必带上 AK/SK**，否则公网连不上）：

```yaml
rocketmq:
  name-server: 38.76.188.242:9876
  producer:
    group: dev_yourname_producer        # 用你自己的名字前缀，见 §6.5
    access-key: rocketmq-student
    secret-key: 5cafa390b8a42c25
    send-message-timeout: 3000
```

**发消息**：

```java
@Resource
private RocketMQTemplate rocketMQTemplate;

public void send() {
    rocketMQTemplate.convertAndSend("dev_yourname_topic", "hello rocketmq");
}
```

**收消息**（消费者的 AK/SK 写在注解上）：

```java
@Component
@RocketMQMessageListener(
    topic = "dev_yourname_topic",
    consumerGroup = "dev_yourname_consumer",
    accessKey = "rocketmq-student",
    secretKey = "5cafa390b8a42c25"
)
public class DemoListener implements RocketMQListener<String> {
    @Override
    public void onMessage(String body) {
        System.out.println("收到: " + body);
    }
}
```

### 6.4 建 Topic

在 Dashboard「Topic → Add/Update」手动建（填 topic 名，broker / cluster 选默认），建好再发消息最稳。

### 6.5 多人隔离

共享同一个 Broker，**topic / group 都加你自己的名字前缀**，别和别人重名：
- topic：`dev_yourname_xxx`
- producer group：`dev_yourname_producer`
- consumer group：`dev_yourname_consumer`

### 6.6 注意

- 公网客户端**必须带 AK/SK**（只有容器内网才在免鉴权白名单里）；漏了会连接/鉴权失败。
- AK/SK 同样别 commit 进 git。
- Broker commitlog 只保留 24h，超期消息会被清，别把它当长期存储。

---

## 7. OpenIM（IM / 音视频，做 DatingApp 用）

> DatingApp 的聊天（单聊 / 会话 / 好友）和 1v1 音视频都接这套 OpenIM（，统一通过 `*.chatvibe.me` 域名访问）。客户端用 OpenIM 官方 SDK 直连，后端按需调 OpenIM 接口给用户发 IM token。

### 7.1 连接信息（公网，走 HTTPS / WSS）

全部经反代到 OpenIM，**统一用域名，别直连容器端口 / IP**：

| 用途 | 地址 |
|---|---|
| OpenIM REST API（会话 / 好友 / 消息等） | `https://nexus-mind.chatvibe.me/api` |
| OpenIM Chat API（注册 / 登录，拿 IM token） | `https://nexus-mind.chatvibe.me/chat` |
| OpenIM WebSocket（SDK 连这个） | `wss://nexus-mind.chatvibe.me/msg_gateway` |
| LiveKit 信令（音视频，SDK 自动用） | `wss://nexus-mind.chatvibe.me/livekit` |
| 管理后台（看用户 / 会话 / 消息） | `https://openim-admin.chatvibe.me` |
| 参考 Web 客户端（开源 demo 部署版） | `https://nexus-mind.chatvibe.me` |

### 7.2 客户端 SDK 接入（主路径）

用 OpenIM 官方 SDK（RN / iOS / Android 用各端 SDK，Web 用 `@openim/wasm-client-sdk`）。两个关键参数：

- `apiAddr` / `apiURL` = `https://nexus-mind.chatvibe.me/api`
- `wsAddr` / `wsURL` = `wss://nexus-mind.chatvibe.me/msg_gateway`

SDK init 后用 **IM token + userID** `login()`，就能收发消息、发起音视频。实现可直接参考开源 demo **openim-electron-demo**（`git clone https://github.com/openimsdk/openim-electron-demo.git`）。

### 7.3 怎么拿用户 / IM token

两种方式，按你的账号体系选：

**A. 用 OpenIM Chat 自带的注册 / 登录（推荐，不需要任何密钥）**

你的用户直接走 Chat API 注册登录，拿到 `imToken`，给 SDK 用：

```bash
# 注册（验证码填万能码 666666；密码一般传 md5）
curl -X POST https://nexus-mind.chatvibe.me/chat/account/register \
  -H 'Content-Type: application/json' -H "operationID: reg-$(date +%s)" \
  -d '{"verifyCode":"666666","platform":5,
       "user":{"nickname":"alice","areaCode":"+86","phoneNumber":"13800000001","password":"<md5>"}}'

# 登录 → 返回 imToken / chatToken / userID
curl -X POST https://nexus-mind.chatvibe.me/chat/account/login \
  -H 'Content-Type: application/json' -H "operationID: login-$(date +%s)" \
  -d '{"areaCode":"+86","phoneNumber":"13800000001","password":"<md5>","platform":5}'
```

拿到的 `imToken` 给 SDK `login()`。（请求字段以 OpenIM chat API 实际版本为准，可对照 `openim-electron-demo` 登录注册时浏览器发的请求。）

**B. 你有自己的账号体系，从后端给用户签 IM token（管理员模式）**

需要 OpenIM **admin secret**（能管理所有用户，权限很大）。流程：后端 `POST /api/auth/get_admin_token`（用 secret）→ `POST /api/user/user_register` 建用户 → `POST /api/auth/get_user_token` 给用户签 token。

> admin secret 是共享环境的高权限密钥，只放在后端（gitignored 的 `deploy/.env.deploy`），别打进客户端。共享 dev 凭据（按红线 #1 例外）：
>
> | 项 | 值 |
> |---|---|
> | OpenIM 后端 API base（管理态） | `https://openim-admin.chatvibe.me/api` |
> | `OPENIM_ADMIN_USER_ID` | `imAdmin` |
> | `OPENIM_ADMIN_SECRET` | `c49af41e5a17a9818c26fed0bbb6846e36e288d6000fe28f` |

### 7.4 管理后台

浏览器开 `https://openim-admin.chatvibe.me`，能看用户列表、会话、消息、在线状态，调试时核对你的用户注册没、消息发到没。（登录账号找管理员要。）

### 7.5 音视频（LiveKit）

1v1 语音 / 视频走 SDK 的通话 API 即可，客户端通常**不持 LiveKit 密钥**（地址和通话 token 由 OpenIM 服务端下发）。浏览器端首次通话会弹麦克风 / 摄像头授权。

后端若要自行签 LiveKit token，共享 dev 密钥（按红线 #1 例外）：

| 项 | 值 |
|---|---|
| `LIVEKIT_API_KEY` | `APIV4yfSfQZRZV5z6XaxmQvHUNbhB1cOAYS8PAMLX5N` |
| `LIVEKIT_SECRET_KEY` | `AjONrVXYHqT3BtctDvyCZCLCOJxdsURRfeMlQUUw` |

### 7.6 多人隔离

共享同一套 OpenIM，**userID / 群 ID 加你自己的名字前缀**（如 `alice_yourname`），别和别人撞 ID；别删别人的测试账号 / 会话。

### 7.7 注意

- 一律走上面的 `https` / `wss` 域名，别直连 `154.x:10001/10002`（可能不通且无 TLS）。
- admin secret / LiveKit 密钥属于服务端密钥，客户端只用 IM token，别把任何密钥打进 App。
- 这是共享环境，注册的测试用户、发的消息别人也看得到，别放敏感信息。

---

## 8. 本地构建 / 部署 / 联调（Jenkins + Docker + Loki/Grafana + proto→Nexus）

> 前面 §1–§7 讲的是「你的服务连远端共享中间件」。这一节讲**你写完代码后怎么把自己的一整套微服务在本机用 Docker 跑起来、看日志、和安卓包联调**。
>
> 整体约定：
> - 你的**业务微服务**（gateway / user / match / im …）全部跑在**本机一个 docker 网络**里，互相用 Nacos 服务发现 + gRPC 调用。
> - 它们依赖的**中间件**（PG / Redis / Nacos / MinIO / RocketMQ / OpenIM）仍然用 §1–§7 的**远端共享**那套，不在本机起。
> - **proto** 打成 **Java + Python 包**发到共享 Nexus（38），业务工程通过包管理器拉，不拷 proto 源码。
> - 部署**用本机的 Jenkins 流水线**完成（build → 镜像 → 起服务），不手动敲。
> - 日志用**本机一套 Loki + Grafana** 看。
> - 安卓包指向你本机的 gateway，联调。

```
[安卓包 APK] --HTTP--> [你本机 gateway:8080] --gRPC(Nacos 发现)--> [user/match/im... 同一 docker 网络]
                                                          |
                                                          +--> 远端共享中间件(38 / 154): PG / Redis / Nacos / MinIO / RocketMQ / OpenIM
  本机另跑: [Jenkins(build+部署)]   [Loki + Grafana(看日志)]
```

### 8.1 proto 打包发布到 Nexus（每人加自己的前缀）

proto 不进业务源码树，统一发到共享 Nexus（`https://nexus.jianjiange.site`，Maven + PyPI 都在上面），业务工程用 Maven / pip 拉。**多人共享一个 Nexus，包名必须按你自己的名字加前缀，别和别人撞**：

| 语言 | 包坐标（把 `<name>` 换成你的名字） |
|---|---|
| Java | groupId `com.dating.<name>.proto`，artifactId `<service>-proto`，version 如 `0.1.0` |
| Python | 包名 `dating-proto-<name>-<service>`，version 如 `0.1.0` |

发布仓库（Nexus credential 找管理员要，下面用占位 `<NEXUS_USER>` / `<NEXUS_PASS>`）：

- Java：snapshot → `maven-snapshots`，release → `maven-releases`
- Python：→ `pypi-hosted`

**发 Java 包**：在 proto 的 Java 模块 `pom.xml` 里把 groupId 设成 `com.dating.<name>.proto`，加上发布地址：

```xml
<distributionManagement>
  <snapshotRepository>
    <id>nexus</id>
    <url>https://nexus.jianjiange.site/repository/maven-snapshots/</url>
  </snapshotRepository>
  <repository>
    <id>nexus</id>
    <url>https://nexus.jianjiange.site/repository/maven-releases/</url>
  </repository>
</distributionManagement>
```

`~/.m2/settings.xml` 配账号（id 要和上面的 `nexus` 一致）：

```xml
<settings>
  <servers>
    <server>
      <id>nexus</id>
      <username>admin</username>
      <password>jianjiange</password>
    </server>
  </servers>
</settings>
```

发布：`mvn -q clean deploy`（版本号带 `-SNAPSHOT` 进 snapshots，纯数字进 releases）。

**发 Python 包**：`pyproject.toml` 把 name 设成 `dating-proto-<name>-<service>`，然后：

```bash
python -m build                 # 产出 dist/*.whl + *.tar.gz
twine upload \
  --repository-url https://nexus.jianjiange.site/repository/pypi-hosted/ \
  -u NEXUS_USER -p NEXUS_PASS \
  dist/*
```

### 8.2 业务工程引用 proto 包（拉取）

**Java**（`pom.xml`）：

```xml
<repositories>
  <repository>
    <id>nexus-public</id>
    <url>https://nexus.jianjiange.site/repository/maven-public/</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.dating.<name>.proto</groupId>
  <artifactId>user-proto</artifactId>
  <version>0.1.0</version>      <!-- 锁定版本，禁止 LATEST/RELEASE/^/~ -->
</dependency>
```

**Python**（`pip.conf` 或 `pip install -i`）：

```ini
[global]
index-url = https://NEXUS_USER:NEXUS_PASS@nexus.jianjiange.site/repository/pypi-group/simple/
```
```bash
pip install dating-proto-<name>-user==0.1.0
```

> 改了 proto 一定要**升版本号**再发（同版本号在 Nexus 只能发一次），然后业务端把依赖版本跟着升。

### 8.3 本机起一个 Jenkins（build + 部署）

每人在本机用 Docker 起一个自己的 Jenkins，让它来 build 镜像 + 起服务（别手动敲 `mvn` / `docker build`）。Jenkins 用 **DooD**（挂宿主 docker.sock），这样它 build 出来的镜像和 `docker compose` 起的容器都在你本机 docker 里。

```bash
docker run -d --name my-jenkins \
  -p 8081:8080 \
  -v jenkins_home:/var/jenkins_home \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --network dating-app \
  jenkins/jenkins:lts
# 初始密码：docker logs my-jenkins 里找，或 docker exec my-jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

浏览器开 http://localhost:8081 装好。建一个 Pipeline 任务，`Jenkinsfile` 大致：

```groovy
pipeline {
  agent any
  stages {
    stage('Build')  { steps { sh 'mvn -B -ntp clean package -DskipTests' } }
    stage('Image')  { steps { sh 'docker build -t dating-user:dev ./user-service' } }
    stage('Deploy') { steps { sh 'docker compose -f docker-compose.app.yml up -d' } }
  }
}
```

> Jenkins 容器要和你的服务在**同一个 docker 网络**（上面 `--network dating-app`），并且 `~/.m2/settings.xml`（含 Nexus 账号）要挂进 Jenkins，才能拉到你发的 proto 包。

### 8.4 本机一个 docker 网络起全部服务

先建网络，再用一份 compose 把你的微服务都拉起来，**统一挂这个网络**，互相用容器名 + Nacos 发现：

```bash
docker network create dating-app
```

`docker-compose.app.yml`（示意，按你的服务增减）：

```yaml
name: dating-app
networks:
  default:
    external: true
    name: dating-app

services:
  gateway:
    image: dating-gateway:dev
    ports: ["8080:8080"]            # 安卓包连这个
    environment:
      SPRING_PROFILES_ACTIVE: dev   # 走 §1 的 application-dev.yml，连远端中间件
  user-service:
    image: dating-user:dev
    environment:
      SPRING_PROFILES_ACTIVE: dev
  match-service:
    image: dating-match:dev
    environment:
      SPRING_PROFILES_ACTIVE: dev
  # im-service / post-service ... 照葫芦画瓢
```

> 这些服务连的 PG/Redis/Nacos/MinIO/RocketMQ/OpenIM 都是远端共享那套（§1–§7 的地址）。**Nacos namespace 一定用你自己的 `dev-yourname`**（§4.2），否则你的 gateway 会通过服务发现调到别的同学的服务上去。

### 8.5 用 Loki + Grafana 看日志

服务日志一律打到 stdout（别写文件），本机起一套 Loki + Grafana + 采集器收 docker 容器日志。把下面加进同一个 compose（或单独一份）：

```yaml
  loki:
    image: grafana/loki:3.0.0
    command: -config.file=/etc/loki/local-config.yaml
    ports: ["3100:3100"]
  promtail:
    image: grafana/promtail:3.0.0
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
    command: -config.file=/etc/promtail/config.yml
    # promtail 配置用 docker_sd 抓所有容器 stdout，按 container 名打 label
  grafana:
    image: grafana/grafana:11.0.0
    ports: ["3000:3000"]            # 浏览器开 http://localhost:3000 (admin/admin)
```

进 Grafana（http://localhost:3000，admin/admin）→ 加 Loki 数据源（URL `http://loki:3100`）→ Explore 里按 `{container="dating-user"}` 这种 label 查你某个服务的日志。

> 关键链路日志记得带 traceId / userId，排联调问题时好按它过滤。

### 8.6 和安卓包联调

1. **起好后端**：`docker compose -f docker-compose.app.yml up -d`，确认 gateway 在 `localhost:8080` 通（`curl localhost:8080/actuator/health` 或你的健康检查）。
2. **装安卓包**：用我给你的 APK。
3. **把 APK 的后端地址指到你本机 gateway**：
   - **模拟器**：宿主机是 `10.0.2.2`，后端填 `http://10.0.2.2:8080`。
   - **真机**（和电脑同一 WiFi）：填你电脑的局域网 IP，如 `http://192.168.1.23:8080`（`ifconfig`/`ipconfig` 查）。
   - 地址在 APK 里怎么改（构建参数 / App 内设置页 / 抓包改 host），以我给包时的说明为准。
4. **IM / 音视频**：聊天和音视频走 OpenIM（§7），APK 里那部分指向 §7 的 `nexus-mind.chatvibe.me` 域名即可，不用你本机起 OpenIM。
5. 出问题先看 Grafana 日志（§8.5）+ 对应中间件控制台（DataGrip / Nacos / MinIO / RocketMQ / OpenIM admin）。

---

## 9. 红线（必看）

1. **生产密码 / key 不许 commit 进任何业务 git 仓库**。**学员共享 dev 凭据**(本文档列出的 `38.76.188.242` 这套)为方便学员快速上手,允许写入 workspace 仓库的 `nacos/<service>-<env>.yaml` 配置模板和本文档;**业务代码 / `application*.yml` / `.env*` 仍走 `${ENV}` 占位**,真值放 Nacos 或本机环境变量。
2. 时间统一 **UTC**，别写死本地时区。
3. 持久层**不写多表 JOIN**；**不读别的服务的库**，跨服务走 gRPC。
4. 这是**共享开发环境**，按 §2.3 / §3.3 / §4.2 / §5.3 / §6.5 / §7.6 / §8.1 各用各的库 / db / namespace / bucket / topic / userID / proto 包前缀，别覆盖别人的数据。
5. 别把这套开发环境的地址 / 密码用到任何生产场景。

---

## 10. 排障

| 现象 | 可能原因 | 处理 |
|---|---|---|
| PG 连不上 / 超时 | 网络不通，或端口写错 | 确认是 `5433`（不是 5432）；`telnet 38.76.188.242 5433` 看通不通 |
| PG `database "xxx" does not exist` | 库名写错 / 没建 | 按 §2.3 建自己的库，或确认现有库名（`\l`） |
| Redis `NOAUTH` / `WRONGPASS` | 没带密码 / 密码错 | `-a` 带上速查表里的密码 |
| MinIO `SignatureDoesNotMatch` | 漏了 path-style | 配置加 `path-style-access: true` |
| MinIO 连 `38.76.188.242:18900` 不通 | 服务器没对外开该端口 | 改用 `https://minio-api.jianjiange.site` |
| Nacos 控制台 403 / 拉不到配置 | 账号密码错，或 namespace 选错 | 账号 `nacos`/`jianjiange`；确认 namespace ID 填对 |
| 服务起来了但 Nacos 服务列表看不到 | namespace 不一致 | 控制台选的 namespace 要和配置里的一致 |
| RocketMQ 连不上 / 鉴权失败 | 没带 AK/SK | producer 配 `access-key`/`secret-key`，consumer 在 `@RocketMQMessageListener` 上加 `accessKey`/`secretKey` |
| RocketMQ 发消息报 topic not exist | topic 没建 | 先在 Dashboard 建 topic（§6.4） |
| OpenIM SDK 连不上 / 登录失败 | apiURL/wsURL 写错，或 IM token 过期 | apiURL=`https://nexus-mind.chatvibe.me/api`、wsURL=`wss://nexus-mind.chatvibe.me/msg_gateway`；重新登录拿新 token |
| OpenIM 注册报验证码错误 | 没用万能码 | verifyCode 填 `666666` |
| 音视频接通但没声画 | 浏览器没给麦克风/摄像头权限 | 允许权限后重试；token 由服务端下发，客户端无需配 LiveKit |
| 拉不到 proto 包 / `Could not find artifact` | 仓库没配，或版本/包名写错 | Java 配 `maven-public` 仓库，Python 配 `pypi-group`；确认包名带了你的 `<name>` 前缀、版本号锁对（§8.1/§8.2） |
| 发 proto 包报 `already exists` / 400 | 同版本号已发过 | 升 `VERSION` / 包版本号再发，同版本只能发一次 |
| gateway 通了但安卓包连不上 | APK 后端地址不对 | 模拟器用 `10.0.2.2:8080`，真机用电脑局域网 IP；确认 gateway 端口映射出来了（§8.6） |
| gateway 调到别的同学的服务 | 共用了同一个 Nacos namespace | 改用你自己的 `dev-yourname` namespace（§4.2）重启服务 |
| Grafana 里看不到日志 | 服务没打到 stdout，或 promtail 没起 | 日志走 stdout；确认 promtail 容器在跑、Loki 数据源 URL 是 `http://loki:3100`（§8.5） |

需要登录服务器看容器 / 日志这种运维操作，找管理员，别自己上去乱动共享机。
