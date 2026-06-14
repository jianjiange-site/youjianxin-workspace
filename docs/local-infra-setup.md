# 本地 Docker 基建搭建指南（Nacos / PG / Redis / MinIO / RocketMQ + 可选 OpenIM / LiveKit）

> 适用范围：新人在自己的**个人机器**（Mac / Windows / CentOS）上用 Docker 一键拉起一套自用的基础设施，方便在 IDE 里跑业务服务时连本地中间件。
>
> 所有组件挂在同一个 Docker 网络 `dating-local` 上，容器之间用容器名互访；宿主机用 `localhost:<host_port>` 访问。
>
> 凭据全部是**本地默认弱密码**，仅供本机开发使用，不要复用到任何服务器。

---

## 0. 速查表

| 资源 | 容器名 | 宿主端口 | 容器内端口 | 网络内地址 | 用户/AK | 密码/SK |
|---|---|---|---|---|---|---|
| PostgreSQL 16 | `local-postgres` | `5432` | `5432` | `local-postgres:5432` | `dating` | `dating123` |
| Redis 7 | `local-redis` | `6379` | `6379` | `local-redis:6379` | — | `redis123` |
| MinIO API | `local-minio` | `9000` | `9000` | `local-minio:9000` | `minioadmin` | `minioadmin123` |
| MinIO Console | `local-minio` | `9001` | `9001` | — | 同上 | 同上 |
| Nacos 2.3 | `local-nacos` | `8848 / 9848 / 9849` | 同 | `local-nacos:8848` | `nacos` | `nacos` |
| RocketMQ NameServer | `local-rmqnamesrv` | `9876` | `9876` | `local-rmqnamesrv:9876` | — | — |
| RocketMQ Broker | `local-rmqbroker` | `10909 / 10911 / 10912` | 同 | `local-rmqbroker:10911` | — | — |
| RocketMQ Dashboard | `local-rmqdashboard` | `8080` | `8080` | — | — | — |

Docker 网络：`dating-local`（bridge）。

> IM / 音视频是可选栈：**OpenIM Server + LiveKit Server** 的本地端口与部署见 [§10](#10-可选openim-server--livekit-server-本地部署)，同样挂在 `dating-local` 网络上，复用本机的 `local-redis` / `local-minio`。

---

## 1. 安装 Docker

三个系统目标版本：Docker Engine ≥ 24，Docker Compose v2（`docker compose` 子命令，不是老的 `docker-compose`）。

### 1.1 macOS

1. 打开 https://www.docker.com/products/docker-desktop/ 下载 Docker Desktop for Mac。Apple Silicon 选 `Apple Chip`，Intel 选 `Intel Chip`。
2. 双击 `.dmg`，把 Docker 拖到 Applications，启动一次，按提示授权。
3. 打开 Docker Desktop → Settings → Resources，把 CPU / Memory 拉到至少 `4 CPU / 6 GB`（RocketMQ broker 自带 JVM，内存少了会 OOM）。
4. 验证：
   ```bash
   docker version
   docker compose version
   docker run --rm hello-world
   ```

### 1.2 Windows

1. 先装 WSL2（Win11 自带，Win10 需手动开）：
   ```powershell
   wsl --install
   ```
   重启后默认装好 Ubuntu。
2. 下载 Docker Desktop for Windows：https://www.docker.com/products/docker-desktop/
3. 安装时勾选 `Use WSL 2 instead of Hyper-V`。
4. 启动 Docker Desktop → Settings → Resources → WSL Integration，开启对默认发行版的集成。
5. Settings → Resources → Advanced，CPU / Memory 拉到至少 `4 CPU / 6 GB`。
6. 验证（PowerShell 或 WSL Ubuntu 终端均可）：
   ```powershell
   docker version
   docker compose version
   docker run --rm hello-world
   ```

> **强烈建议在 WSL2 的 Linux 文件系统里跑 compose**（例如 `~/dating-local/`），不要放到 `C:\Users\xxx\...` 这种 Windows 路径下；前者磁盘 IO 是后者的几十倍，且不会有换行符 / 文件权限问题。

### 1.3 CentOS（7 / 8 / Stream 9 通用）

```bash
# 1. 卸掉老版本（如有）
sudo yum remove -y docker docker-client docker-client-latest docker-common \
                   docker-latest docker-latest-logrotate docker-logrotate docker-engine

# 2. 安装仓库
sudo yum install -y yum-utils
sudo yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo

# 3. 安装 Docker Engine + Compose 插件
sudo yum install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 4. 开机自启 + 启动
sudo systemctl enable --now docker

# 5. 把当前用户加入 docker 组（免 sudo），重新登录生效
sudo usermod -aG docker "$USER"
newgrp docker

# 6. 验证
docker version
docker compose version
docker run --rm hello-world
```

> 如果是阿里云 / 腾讯云的 CentOS，建议把 Docker 镜像加速器配上，编辑 `/etc/docker/daemon.json`：
> ```json
> { "registry-mirrors": ["https://docker.m.daocloud.io"] }
> ```
> `sudo systemctl restart docker` 后生效。

---

## 2. 创建共享网络

三个系统都一样：

```bash
docker network create dating-local
```

校验：

```bash
docker network ls | grep dating-local
```

---

## 3. 准备工作目录

约定一个本地目录放 compose + 持久化数据 + 配置。Mac / Linux 在 `~/dating-local`，Windows 在 WSL 下的 `~/dating-local`。

```bash
mkdir -p ~/dating-local/{data/postgres,data/redis,data/minio,data/nacos,data/rmq/namesrv,data/rmq/broker,conf/rmq,conf/nacos}
cd ~/dating-local
```

### 3.1 RocketMQ broker 配置

RocketMQ broker 默认会把自己注册到 nameserver 时上报"宿主机内网 IP"，在 Docker 网络里这个 IP 是不可达的，必须显式覆盖。

写一个 `conf/rmq/broker.conf`：

```ini
brokerClusterName = DefaultCluster
brokerName = broker-a
brokerId = 0
deleteWhen = 04
fileReservedTime = 48
brokerRole = ASYNC_MASTER
flushDiskType = ASYNC_FLUSH
# 关键：客户端 / 其他容器拿到的 broker 地址。
# 本机 IDE 跑业务时通过宿主端口直连，设 127.0.0.1 即可。
# 如果业务服务也在 dating-local 网络里以容器形式跑，请改成 local-rmqbroker。
brokerIP1 = 127.0.0.1
# 自动建 topic，省事；上生产前关掉
autoCreateTopicEnable = true
autoCreateSubscriptionGroup = true
```

> 反复踩坑提醒：`brokerIP1` 改了之后，**broker 容器要 `docker compose restart local-rmqbroker`** 才能重新注册到 namesrv。

---

## 4. docker-compose.yml

把下面这份完整粘到 `~/dating-local/docker-compose.yml`：

```yaml
name: dating-local

networks:
  default:
    external: true
    name: dating-local

services:
  postgres:
    image: postgres:16-alpine
    container_name: local-postgres
    restart: unless-stopped
    environment:
      POSTGRES_USER: dating
      POSTGRES_PASSWORD: dating123
      POSTGRES_DB: dating_local
      TZ: UTC
      PGTZ: UTC
    command: ["postgres", "-c", "timezone=UTC"]
    ports:
      - "5432:5432"
    volumes:
      - ./data/postgres:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U dating -d dating_local"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    container_name: local-redis
    restart: unless-stopped
    command: ["redis-server", "--requirepass", "redis123", "--appendonly", "yes"]
    ports:
      - "6379:6379"
    volumes:
      - ./data/redis:/data
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "redis123", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  minio:
    image: minio/minio:latest
    container_name: local-minio
    restart: unless-stopped
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin123
    command: server /data --console-address ":9001"
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - ./data/minio:/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 10s
      timeout: 5s
      retries: 5

  nacos:
    image: nacos/nacos-server:v2.3.2
    container_name: local-nacos
    restart: unless-stopped
    environment:
      MODE: standalone
      PREFER_HOST_MODE: hostname
      NACOS_AUTH_ENABLE: "true"
      NACOS_AUTH_TOKEN: SecretKey012345678901234567890123456789012345678901234567890123456789
      NACOS_AUTH_IDENTITY_KEY: serverIdentity
      NACOS_AUTH_IDENTITY_VALUE: security
      JVM_XMS: 512m
      JVM_XMX: 512m
      JVM_XMN: 256m
    ports:
      - "8848:8848"
      - "9848:9848"
      - "9849:9849"
    volumes:
      - ./data/nacos:/home/nacos/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8848/nacos/actuator/health"]
      interval: 15s
      timeout: 5s
      retries: 10

  rmqnamesrv:
    image: apache/rocketmq:5.2.0
    container_name: local-rmqnamesrv
    restart: unless-stopped
    command: sh mqnamesrv
    environment:
      JAVA_OPT_EXT: "-Xms256m -Xmx256m -Xmn128m"
    ports:
      - "9876:9876"
    volumes:
      - ./data/rmq/namesrv:/home/rocketmq/logs

  rmqbroker:
    image: apache/rocketmq:5.2.0
    container_name: local-rmqbroker
    restart: unless-stopped
    depends_on:
      - rmqnamesrv
    command: sh mqbroker -n local-rmqnamesrv:9876 -c /home/rocketmq/conf/broker.conf
    environment:
      JAVA_OPT_EXT: "-Xms512m -Xmx512m -Xmn256m"
    ports:
      - "10909:10909"
      - "10911:10911"
      - "10912:10912"
    volumes:
      - ./conf/rmq/broker.conf:/home/rocketmq/conf/broker.conf:ro
      - ./data/rmq/broker:/home/rocketmq/logs

  rmqdashboard:
    image: apacherocketmq/rocketmq-dashboard:latest
    container_name: local-rmqdashboard
    restart: unless-stopped
    depends_on:
      - rmqnamesrv
    environment:
      JAVA_OPTS: "-Drocketmq.namesrv.addr=local-rmqnamesrv:9876"
    ports:
      - "8080:8080"
```

> 这里的 `networks.default.external: true` 是关键，让所有服务都挂到第 2 步创建的 `dating-local` 上，业务服务起容器时只要 `--network dating-local` 就能直接 `local-postgres:5432` 这样互访。

---

## 5. 启动 & 健康检查

```bash
cd ~/dating-local
docker compose up -d
docker compose ps
```

期望状态：所有服务 `Up`，PG/Redis/MinIO/Nacos 的 `healthy`。

逐项验证：

```bash
# PostgreSQL
docker exec -it local-postgres psql -U dating -d dating_local -c '\l'

# Redis
docker exec -it local-redis redis-cli -a redis123 ping        # 期望 PONG

# MinIO（浏览器打开 http://localhost:9001，用 minioadmin / minioadmin123 登录）
curl -sf http://localhost:9000/minio/health/live && echo MINIO_OK

# Nacos（浏览器打开 http://localhost:8848/nacos，用 nacos / nacos 登录）
curl -sf http://localhost:8848/nacos/actuator/health

# RocketMQ Dashboard：浏览器打开 http://localhost:8080
# 命令行验证 broker 注册成功（应能看到 brokerAddr=127.0.0.1:10911）
docker exec -it local-rmqbroker sh -c "cd ../tools && sh mqadmin clusterList -n local-rmqnamesrv:9876"
```

---

## 6. 各组件本地初始化

### 6.1 PostgreSQL 建业务库

`compose` 已经默认建了 `dating_local`。如果业务侧要按服务一库（比如 `dating_chat`、`dating_im`、`dating_payment`），登录后手动建：

```bash
docker exec -it local-postgres psql -U dating -d postgres -c "
CREATE DATABASE dating_chat;
CREATE DATABASE dating_im;
CREATE DATABASE dating_payment;
"
```

> 业务表结构走各服务 `src/main/resources/db/migration` 下的 Flyway / 自带 SQL 脚本，本文档不涉及。

### 6.2 Redis

不需要初始化。本地默认走 db 0；业务连接串里用 `database=<n>` 指定即可。

### 6.3 MinIO 建 bucket

浏览器进 http://localhost:9001 → Buckets → Create Bucket，按服务命名 `dating-<service>`，例如：

- `dating-user`
- `dating-im`
- `dating-payment`

或者命令行用 `mc` 客户端：

```bash
docker run --rm --network dating-local minio/mc \
  sh -c "mc alias set local http://local-minio:9000 minioadmin minioadmin123 && \
         mc mb -p local/dating-user local/dating-im local/dating-payment"
```

> 公开资产对应的 bucket 创建后需要把 anonymous policy 设成 `download`（在 Console 里 Access → Anonymous → Add Access Rule，Prefix `*`，Access `readonly`），否则 App 自拼 URL 拉不到。敏感资产 bucket 保持 private + 后端签 GET URL。

### 6.4 Nacos namespace

浏览器进 http://localhost:8848/nacos（nacos / nacos）→ 命名空间 → 新建命名空间：

- 命名空间名 `dating_chat_local`
- 命名空间 ID 留空（自动生成 UUID），或者手填 `dating_chat_local` 方便记

把 namespace ID 记下来，业务服务 `application.yml` 里写：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
        namespace: dating_chat_local
        username: nacos
        password: nacos
      config:
        server-addr: localhost:8848
        namespace: dating_chat_local
        username: nacos
        password: nacos
        file-extension: yaml
```

### 6.5 RocketMQ 建 topic（可选）

`broker.conf` 里已经 `autoCreateTopicEnable = true`，业务首次发消息就会自动建 topic，开发阶段不必预建。要预建用 dashboard 或：

```bash
docker exec -it local-rmqbroker sh -c "cd ../tools && \
  sh mqadmin updateTopic -n local-rmqnamesrv:9876 -c DefaultCluster -t demo_topic"
```

---

## 7. 业务服务接入示例

> 假设业务服务在宿主机 IDE 里跑（最常见的本地开发姿势）。

### 7.1 application-local.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/dating_chat?currentSchema=public
    username: dating
    password: dating123
  data:
    redis:
      host: localhost
      port: 6379
      password: redis123
      database: 0
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
        namespace: dating_chat_local
        username: nacos
        password: nacos
      config:
        server-addr: localhost:8848
        namespace: dating_chat_local
        username: nacos
        password: nacos
        file-extension: yaml

# dating-common ObjectStorage
storage:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin123
  bucket: dating-user
  region: us-east-1

# RocketMQ
rocketmq:
  name-server: localhost:9876
  producer:
    group: local-producer
```

### 7.2 业务服务也跑在容器里的情况

如果想把业务服务用 `docker run --network dating-local` 起到同一网络，把上面的 host 换成容器名即可：`local-postgres / local-redis / local-minio / local-nacos:8848 / local-rmqnamesrv:9876`。

**注意**：这种姿势下 RocketMQ 的 `broker.conf` 里 `brokerIP1` 必须改成 `local-rmqbroker`（不再是 127.0.0.1），否则业务容器发消息会卡在 connect timeout —— broker 把 `127.0.0.1` 返回给业务容器，业务容器去连自己的 127.0.0.1 当然连不上 broker。改完 `docker compose restart rmqbroker`。

---

## 8. 常见坑

| 现象 | 根因 | 处理 |
|---|---|---|
| `docker compose up` 报 `network dating-local not found` | 没跑第 2 步 | `docker network create dating-local` |
| Nacos 反复重启 / 日志一堆 `derby` 报错 | 数据卷脏了 | `docker compose down && rm -rf data/nacos && docker compose up -d nacos` |
| Nacos 登录页一直转圈 | `NACOS_AUTH_*` 三件套配不全，2.2+ 强制 | 用本文 compose 里的完整配置；不要只配 `NACOS_AUTH_ENABLE` |
| RocketMQ 生产者 `connect to 172.x.x.x:10911 failed` | `brokerIP1` 写成了 docker 内网 IP，宿主机不可达 | 改 `broker.conf` 为 `brokerIP1 = 127.0.0.1`（IDE 跑业务）或 `local-rmqbroker`（业务也在 docker 里），重启 broker |
| RocketMQ 起来后 dashboard 看到 0 broker | broker 启动早于 namesrv | `docker compose restart rmqbroker` |
| MinIO Console 9001 打不开 | command 里没带 `--console-address` | 用本文 compose 配置；老版镜像 console 端口随机 |
| Windows 下 compose 启动很慢 / 文件挂载 IO 极慢 | 工作目录在 `C:\Users\...` | 把工作目录搬到 WSL 内（`\\wsl$\Ubuntu\home\<user>\dating-local`） |
| CentOS 上 `docker` 命令需要 sudo | 用户没加 docker 组 | `sudo usermod -aG docker $USER && newgrp docker` |
| PG 时区不对 | 漏了 `TZ=UTC` + `command: postgres -c timezone=UTC` | 业务统一按 UTC 存取，按本文 compose 配置 |
| 内存占用直奔 8G+ | RocketMQ broker 默认 JVM 8G | 本文已经在 `JAVA_OPT_EXT` 里压到 512m，如果你改回默认会爆 |

---

## 9. 启停 / 清理 / 重建

```bash
# 停（保留数据）
docker compose stop

# 起
docker compose start

# 完全销毁（容器 + 网络绑定，但保留 ./data）
docker compose down

# 连数据一起清掉，回到初始状态
docker compose down
rm -rf ./data

# 单独重启某个组件
docker compose restart rmqbroker

# 看某个组件日志
docker compose logs -f nacos
```

---

## 10. （可选）OpenIM Server + LiveKit Server 本地部署

> 只有要在本机联调 IM（文字消息 / 好友 / 会话）或 1v1 音视频时才需要这套；纯业务接口开发不用起。
>
> 用官方 OpenIM / LiveKit 镜像，跑在和基建同一个 `dating-local` 网络里，复用本机已有的 `local-redis` / `local-minio`，不重复起 Redis / 对象存储。

### 10.1 速查表（OpenIM / LiveKit）

| 资源 | 容器名 | 宿主端口 | 网络内地址 | 备注 |
|---|---|---|---|---|
| OpenIM REST API | `openim-server` | `10002` | `openim-server:10002` | im-service 调它（admin secret） |
| OpenIM WebSocket | `openim-server` | `10001` | `openim-server:10001` | 客户端 OpenIM SDK 直连 |
| OpenIM Chat API | `openim-chat` | `10008 / 10009` | `openim-chat:10008` | 管理后台 / Chat 业务 |
| OpenIM 管理后台前端 | `openim-admin-front` | `11002` | — | 浏览器 `http://localhost:11002` |
| LiveKit 信令（WS） | `openim-livekit` | `7880` | `openim-livekit:7880` | 客户端 LiveKit SDK 直连 |
| LiveKit TCP 回退 | `openim-livekit` | `7881` | — | UDP 不通时回退 |
| LiveKit UDP mux | `openim-livekit` | `7882/udp` | — | 媒体流（单端口复用） |
| MongoDB（IM 专用） | `openim-mongo` | 不暴露 | `mongo:27017` | 只给 OpenIM 用，业务别直连 |
| Kafka（IM 专用，KRaft） | `openim-kafka` | 不暴露 | `kafka:9092` | 只给 OpenIM 用，业务别直连 |
| Etcd（IM 服务发现） | `openim-etcd` | 不暴露 | `etcd:2379` | 只给 OpenIM 用，业务别直连 |

> 复用本机 `local-redis:6379`（OpenIM 单独用 db 3 隔离，密码 `redis123`）和 `local-minio:9000`（bucket `dating-im`），不另起容器。MongoDB / Kafka / Etcd 是 OpenIM 引擎自己的内部依赖，业务代码一律经 `im-service` 的 gRPC，别去直连它们。

### 10.2 取得 OpenIM 配置

OpenIM 启动要一整套配置文件：一套给 openim-server，一套给 openim-chat。这些是 OpenIM 的标准开源配置，从官方部署仓库取一份默认模板放进工作目录即可：

```bash
mkdir -p ~/dating-local/open-im
cd ~/dating-local/open-im

# 从 OpenIM 官方部署仓库取默认配置（含 server 与 chat 两套）
git clone https://github.com/openimsdk/openim-docker.git /tmp/openim-docker

# server 配置 -> ./config，chat 配置 -> ./chat-config（具体子目录以官方仓库实际结构为准）
cp -r /tmp/openim-docker/openim-server/config   ./config
cp -r /tmp/openim-docker/openim-chat/config     ./chat-config
mkdir -p livekit
```

> 官方默认配置里的地址 / 密码指向它自带的那套部署，下一步只改我们关心的几项，让它指到本机的 `local-redis` / `local-minio` 和 compose 新起的 mongo / kafka / etcd。
>
> 不同 OpenIM 版本目录 / 字段会略有差异，对不上很正常——用 [§11](#11-不会-docker让-claude-code-照着这份文档帮你部署) 的 Claude Code 来装时，直接让它"取 OpenIM v3.8 的 server / chat 配置放进 `./config` 和 `./chat-config`，并按 10.3 改成本机地址"，它会处理差异。

### 10.3 改这几处配置（直接写死本机地址）

只改下面几个文件，其它保持默认。本地弱密码直接写进配置即可。

**① `config/redis.yml` 和 `chat-config/redis.yml`** —— Redis 指向本机 `local-redis`，密码 `redis123`，OpenIM 用 db 3 隔离：

```yaml
# config/redis.yml
address: [ local-redis:6379 ]
username:
password: redis123
clusterMode: false
db: 3
maxRetry: 10
poolSize: 100
```

```yaml
# chat-config/redis.yml
address: [ local-redis:6379 ]
username: ''
password: redis123
enablePipeline: false
clusterMode: false
db: 3
maxRetry: 10
```

**② `config/mongodb.yml` 和 `chat-config/mongodb.yml`** —— 指向 compose 新起的 mongo（账号 root / mongo123，要和 [§10.4](#104-docker-composeyml) 的 compose 一致）：

```yaml
# config/mongodb.yml（chat-config/mongodb.yml 同样这么改）
uri:
address: [ mongo:27017 ]
database: openim_v3
username: root
password: mongo123
authSource: admin
maxPoolSize: 100
maxRetry: 10
```

**③ `config/discovery.yml`（含 `chat-config/discovery.yml`）和 `config/kafka.yml`** —— 服务发现 / 消息队列指向 compose 新起的 etcd / kafka（默认大概率已是这俩，对不上就改）：

```yaml
# config/discovery.yml（etcd 段）
enable: etcd
etcd:
  rootDirectory: openim
  address: [ etcd:2379 ]
```

```yaml
# config/kafka.yml（地址段）
address: [ kafka:9092 ]
```

**④ `config/minio.yml`** —— 对象存储指向本机 MinIO（http）：

```yaml
# config/minio.yml
bucket: dating-im
accessKeyID: minioadmin
secretAccessKey: minioadmin123
sessionToken:
internalAddress: http://local-minio:9000     # openim-server 容器内访问走容器名
externalAddress: http://localhost:9000        # 下发给客户端拉媒体；真机走 LAN 改成宿主机 LAN IP
publicRead: false
```

> 先确认 `dating-im` bucket 已建（[§6.3](#63-minio-建-bucket)）；OpenIM 会往里写头像 / 图片 / 语音消息附件。

**⑤ `config/share.yml` 和 `chat-config/share.yml`** —— OpenIM admin secret，im-service 调 REST、下面健康检查都要用它。**记住你设的值**：

```yaml
# config/share.yml
secret: local-openim-secret
imAdminUserID: [ imAdmin ]
```

```yaml
# chat-config/share.yml（openIM 段）
openIM:
  apiURL: http://openim-server:10002
  secret: local-openim-secret
  adminUserID: imAdmin
```

**⑥ 新建 `livekit/livekit.yaml`** —— bridge + 单 UDP mux 端口（Docker Desktop 的 host 网络对 UDP 不可靠，本地别用 host 网络）：

```yaml
# livekit/livekit.yaml
port: 7880
rtc:
  tcp_port: 7881
  udp_port: 7882            # 单 UDP mux 端口，省掉映射 50000-60000 上万 UDP 端口
  use_external_ip: false
  node_ip: 127.0.0.1        # 真机在同一 LAN 调试时改成宿主机局域网 IP
keys:
  devkey: devsecret_please_use_32_plus_characters_xx   # 格式 key: secret，secret 要 >= 32 字符
turn:
  enabled: false
logging:
  level: info
  json: false
room:
  empty_timeout: 60
  max_participants: 4
```

**⑦ `chat-config/chat-rpc-chat.yml`** —— OpenIM 在 1v1 通话时把 `liveKit.url` 下发给客户端，必须改成浏览器能访问的本机地址；key / secret 要和 ⑥ 里那对**完全一致**：

```yaml
# chat-config/chat-rpc-chat.yml（liveKit 段）
liveKit:
  url: "ws://localhost:7880"     # 真机走 LAN 时改成 ws://<宿主机 LAN IP>:7880
  key: "devkey"
  secret: "devsecret_please_use_32_plus_characters_xx"
```

> 同一文件里确认 `allowRegister: true`（允许客户端自助注册）、`verifyCode.superCode: "666666"`（万能验证码，下面注册账号填它，免真实短信）；默认值不同就按这里改。

### 10.4 `docker-compose.yml`

写 `~/dating-local/open-im/docker-compose.yml`：

```yaml
name: dating-openim-local

networks:
  dating-local:
    external: true

volumes:
  openim_mongo_data:
  openim_kafka_data:
  openim_logs:
  # 持久化 mage 编译产物，避免每次 recreate 都现场重编 Go 源码
  # 升级镜像 tag 时记得 docker volume rm 掉这几个 *-output / *-magefile
  openim-server-output:
  openim-server-magefile:
  openim-chat-output:
  openim-chat-magefile:

services:
  # ---------- OpenIM 专用中间件 ----------
  mongo:
    image: mongo:7.0
    container_name: openim-mongo
    restart: unless-stopped
    networks: [dating-local]
    environment:
      MONGO_INITDB_ROOT_USERNAME: root
      MONGO_INITDB_ROOT_PASSWORD: mongo123
      MONGO_INITDB_DATABASE: openim_v3
      TZ: UTC
    command: ["--wiredTigerCacheSizeGB", "0.5"]
    volumes:
      - openim_mongo_data:/data/db
    healthcheck:
      test: ["CMD", "mongosh", "--quiet", "--eval", "db.runCommand({ ping: 1 }).ok"]
      interval: 10s
      timeout: 10s
      retries: 10
      start_period: 40s
    mem_limit: 2g

  kafka:
    image: bitnamilegacy/kafka:3.8
    container_name: openim-kafka
    restart: unless-stopped
    networks: [dating-local]
    environment:
      KAFKA_CFG_NODE_ID: "0"
      KAFKA_CFG_PROCESS_ROLES: controller,broker
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 0@kafka:9093
      KAFKA_CFG_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE: "true"
      KAFKA_HEAP_OPTS: "-Xmx512m -Xms512m"
      ALLOW_PLAINTEXT_LISTENER: "yes"
      TZ: UTC
    volumes:
      - openim_kafka_data:/bitnami/kafka
    healthcheck:
      test: ["CMD-SHELL", "bash -c '</dev/tcp/localhost/9092'"]
      interval: 15s
      timeout: 3s
      retries: 12
      start_period: 40s
    mem_limit: 1g

  etcd:
    image: bitnamilegacy/etcd:3.5
    container_name: openim-etcd
    restart: unless-stopped
    networks: [dating-local]
    environment:
      ALLOW_NONE_AUTHENTICATION: "yes"
      ETCD_NAME: openim-etcd
      ETCD_ADVERTISE_CLIENT_URLS: http://etcd:2379
      ETCD_LISTEN_CLIENT_URLS: http://0.0.0.0:2379
      ETCD_LISTEN_PEER_URLS: http://0.0.0.0:2380
      ETCD_INITIAL_ADVERTISE_PEER_URLS: http://etcd:2380
      ETCD_INITIAL_CLUSTER: openim-etcd=http://etcd:2380
      ETCD_INITIAL_CLUSTER_TOKEN: openim-etcd-cluster
      ETCD_INITIAL_CLUSTER_STATE: new
      ETCD_UNSAFE_NO_FSYNC: "true"
      TZ: UTC
    tmpfs:
      - /bitnami/etcd:size=128m,mode=1777
    healthcheck:
      test: ["CMD", "etcdctl", "endpoint", "health"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 15s
    mem_limit: 512m

  # ---------- OpenIM 主体 ----------
  openim-server:
    image: openim/openim-server:v3.8.3-patch.16
    container_name: openim-server
    restart: unless-stopped
    networks: [dating-local]
    depends_on:
      mongo: { condition: service_healthy }
      kafka: { condition: service_healthy }
      etcd:  { condition: service_healthy }
    environment:
      TZ: UTC
    volumes:
      - ./config:/openim-server/config:ro
      - openim_logs:/openim-server/logs
      - openim-server-output:/openim-server/_output
      - openim-server-magefile:/root/.magefile
    ports:
      - "10001:10001"   # WebSocket（msggateway）
      - "10002:10002"   # REST API
    mem_limit: 4g

  openim-chat:
    image: openim/openim-chat:v1.8.4-patch.4
    container_name: openim-chat
    restart: unless-stopped
    networks: [dating-local]
    depends_on:
      - openim-server
    environment:
      TZ: UTC
    volumes:
      - ./chat-config:/openim-chat/config:ro
      - openim-chat-output:/openim-chat/_output
      - openim-chat-magefile:/root/.magefile
    ports:
      - "10008:10008"
      - "10009:10009"
    mem_limit: 1g

  openim-admin-front:
    image: openim/openim-admin-front:release-v1.8.4-patch.2
    container_name: openim-admin-front
    restart: unless-stopped
    networks: [dating-local]
    ports:
      - "11002:80"
    environment:
      TZ: UTC
    mem_limit: 128m

  # ---------- LiveKit（bridge + 单 UDP mux 端口） ----------
  livekit:
    image: livekit/livekit-server:v1.7
    container_name: openim-livekit
    restart: unless-stopped
    networks: [dating-local]
    command: ["--config", "/livekit.yaml"]
    environment:
      TZ: UTC
    volumes:
      - ./livekit/livekit.yaml:/livekit.yaml:ro
    ports:
      - "7880:7880"        # 信令 WS
      - "7881:7881"        # TCP 回退
      - "7882:7882/udp"    # UDP mux 媒体
    mem_limit: 1g
```

> 所有服务都挂 `dating-local`，service 名 `mongo` / `kafka` / `etcd` 即网络别名，正好对上 [§10.3](#103-改这几处配置直接写死本机地址) 配置里的 `mongo:27017` / `kafka:9092` / `etcd:2379`。`local-redis` / `local-minio` 由 [§4](#4-docker-composeyml) 的基建 compose 提供，确保它们已经 `Up`。

### 10.5 启动 & 健康检查

```bash
cd ~/dating-local/open-im
docker compose up -d
docker compose ps
```

> ⚠️ **首次启动 `openim-server` / `openim-chat` 会在容器里现场 `mage` 编译 Go 源码**，慢且吃 CPU/内存（几分钟级别）。编译产物落在上面的 `*-output` / `*-magefile` 卷里，之后 recreate 不再重编。跑这套时建议 Docker Desktop 给到 **≥ 6 CPU / 10 GB**；内存紧张时先把 RocketMQ 停掉（`cd ~/dating-local && docker compose stop rmqbroker rmqnamesrv rmqdashboard`）。

逐项验证：

```bash
# OpenIM admin token（拿到 token 即 REST 通）
curl -sf -X POST http://localhost:10002/auth/get_admin_token \
  -H 'Content-Type: application/json' \
  -H "operationID: local-$(date +%s)" \
  -d '{"secret":"local-openim-secret","userID":"imAdmin"}'

# OpenIM 管理后台：浏览器 http://localhost:11002
# LiveKit 健康（返回 OK)
curl -sf http://localhost:7880 && echo LIVEKIT_OK
```

### 10.6 用 Web 客户端联调（注册两个账号互发消息 / 语音）

OpenIM 官方开源了一个 Web / 桌面客户端 **openim-electron-demo**（React + `@openim/wasm-client-sdk` + LiveKit，AGPL-3.0），用它在浏览器里注册两个账号、加好友、发文字 / 语音、打 1v1 语音视频，是验证整套 IM 最直接的方式。

> 它既能跑成网页（Web），也能打包成桌面 App（Electron）。本地联调用 Web（dev server）最快。

**前置**：Node.js ≥ 16（`node -v` 确认）、Git。

**1) 拉源码**

```bash
cd ~/dating-workspace   # 放哪都行，按你本机习惯
git clone https://github.com/openimsdk/openim-electron-demo.git
cd openim-electron-demo
```

**2) 配置服务器地址** —— 编辑项目根目录的 `.env`，把里面的 OpenIM 地址指向本机（字段名以仓库实际 `.env` 为准，当前版本是这 3 个）：

```bash
# .env（本地直连本机 OpenIM）
VITE_WS_URL=ws://localhost:10001     # OpenIM WebSocket（msggateway）
VITE_API_URL=http://localhost:10002  # OpenIM REST API
VITE_CHAT_URL=http://localhost:10008 # OpenIM Chat API（注册/登录）
```

> 通话用的 LiveKit 地址不在客户端 `.env` 里配，是 OpenIM 在拨号时下发给客户端的——已经在 [§10.3 ⑦](#103-改这几处配置直接写死本机地址) 把 `chat-rpc-chat.yml` 的 `liveKit.url` 改成 `ws://localhost:7880` 了。

**3) 装依赖 + 起 dev server**

```bash
npm install
npm run dev
```

浏览器开 http://localhost:5173。

**4) 注册两个账号** —— 登录页点「注册」，填手机号（随便编，如 `13800000001`）+ 昵称 + 密码，**验证码填 `666666`**；换个号（如 `13800000002`）再注册第二个。

> 两个账号要同时在线：一个用普通窗口，另一个用**无痕窗口**（或换个浏览器）。同一浏览器的普通窗口共享本地存储，会把先登的挤下线。

**5) 加好友 + 收发** —— 用账号 A 搜索账号 B 的 userID（注册成功后个人资料里能看到）加好友；通过后：
- 发**文字**：直接打字发送。
- 发**语音消息**：消息框的麦克风图标，按住录、松开发。
- **1v1 语音 / 视频通话**：会话右上角的电话 / 摄像头图标拨给对方，对方接听——这条链路走 LiveKit（信令 `ws://localhost:7880` + 媒体 `udp 7882`），浏览器会弹麦克风 / 摄像头授权。

两个窗口能互相看到消息、听到语音、通起来，整套 OpenIM + LiveKit 本地环境就算通了。

> 想更接近"部署一个前端服务"而不是 dev server：`npm run build` 出 `dist/`，再用一个 nginx 容器挂到 `dating-local` 把 `dist/` 当静态站点伺服，并把 `/api`→10002、`/chat`→10008、`/msg_gateway`→10001、`/livekit`→7880 反代到同源（这样可绕开跨端口 CORS）。本地联调用上面的 dev server 就够了。

### 10.7 im-service 本地接入

业务侧只有 `im-service` 直连 OpenIM / LiveKit，其它服务一律经它的 gRPC。`im-service` 跑在宿主机 IDE 时（最常见），连接坐标：

```yaml
# im-service application-local.yml（键名以 im-service 实际配置为准，这里给坐标）
openim:
  api-url: http://localhost:10002          # REST（签 admin token / 注册用户 / 发消息）
  ws-url:  ws://localhost:10001            # 下发给客户端的 WS 地址
  secret:  local-openim-secret
  admin-user-id: imAdmin

livekit:
  url:        ws://localhost:7880          # 下发给客户端
  api-key:    devkey
  api-secret: devsecret_please_use_32_plus_characters_xx
```

> 若把 `im-service` 也用 `docker run --network dating-local` 起到同一网络，把上面 host 换成容器名：`http://openim-server:10002`、`ws://openim-server:10001`、`ws://openim-livekit:7880`。但**下发给客户端**的 `ws-url` / livekit `url` / MinIO `externalAddress` 必须是客户端能访问的地址（本机模拟器用 `localhost`，真机用宿主机 LAN IP），不能给客户端容器名。

### 10.8 常见坑

| 现象 | 根因 | 处理 |
|---|---|---|
| `openim-server` 起来很久没就绪 | 首次在容器里 mage 编译 Go | 正常，等几分钟；`docker compose logs -f openim-server` 看编译进度 |
| 升级镜像 tag 后行为还是旧的 | 旧二进制缓存在 `*-output` 卷里 | `docker volume rm dating-openim-local_openim-server-output dating-openim-local_openim-chat-output` 后重起 |
| `openim-server` 报连不上 redis | `config/redis.yml` 没指到 `local-redis`，或 `local-redis` 没起 | 改 `config/redis.yml` + `chat-config/redis.yml` 后重起；确认基建 compose 已 `Up` |
| 注册/发消息报对象存储错误 | `dating-im` bucket 不存在 / minio.yml 没改成本机 | 建 bucket（§6.3）+ 覆盖 `config/minio.yml` 后重新 gen |
| LiveKit 能进房但听不到声/看不到画面 | UDP mux 端口没通，或 `node_ip` 不对 | 确认 `7882/udp` 映射出来；真机调试把 `node_ip` 改成宿主机 LAN IP 并重起 livekit |
| LiveKit 用 `network_mode: host` 后 Mac 上连不上 | Docker Desktop 的 host 网络在 Mac/Win 不通宿主 | 本地必须用本文的 bridge + `udp_port` 方案 |
| 改了配置不生效 | 改完没重起容器 | `docker compose up -d`（必要时 `docker compose restart openim-server openim-chat`） |
| 内存直奔满 / 容器被 OOM kill | OpenIM 全栈 + 基建同时跑 | Docker Desktop 提到 ≥10G；不用音视频时只起 OpenIM，不用 IM 时整套 `docker compose stop` |
| Web 客户端注册报"验证码错误" | 没用 superCode | 验证码填 `666666`（`chat-rpc-chat.yml` 的 `superCode`） |
| Web 客户端登录后一直转 / 连不上 | `.env` 的 `VITE_WS/API/CHAT` 没指到本机端口 | 改成 `localhost:10001/10002/10008`，重起 `npm run dev` |
| 通话能接通但没声音 / 画面 | `chat-rpc-chat.yml` 的 `liveKit.url` 没改本机，或 `7882/udp` 没通 | §10.3 ⑦ 改成 `ws://localhost:7880` 后重起；确认 livekit 容器 `7882/udp` 映射出来 |
| 浏览器控制台 CORS 报错 | 跨端口直连被浏览器拦 | 确认页面是 `http://localhost:5173`（非 https）；仍报错改用 build + nginx 同源反代方案（§10.6 末） |
| 两个账号在同一普通窗口互相挤下线 | 共享 localStorage / IndexedDB | 第二个账号用无痕窗口或另一个浏览器 |

### 10.9 启停 / 清理

```bash
cd ~/dating-local/open-im

# 停（保留数据 + 编译缓存）
docker compose stop

# 起
docker compose start

# 销毁容器（保留卷里的 mongo 数据 + 编译产物）
docker compose down

# 连数据 + 编译缓存一起清掉（回到初始，下次首启会重新编译）
docker compose down -v

# 看某个组件日志
docker compose logs -f openim-server   # 可选: mongo kafka etcd openim-chat livekit
```

---

## 11. 不会 Docker？让 Claude Code 照着这份文档帮你部署

如果你对 Docker / 命令行不熟，可以把**这份文档直接交给 Claude Code（CC）**，让它逐条执行、遇到报错自己排查修复，全程你只需要看着 + 在它请求权限时点同意。本质上这篇文档就是写给人也写给 AI 的部署说明书。

> CC 是 Anthropic 官方的命令行 AI 编程工具，能在你授权下读写文件、跑 shell 命令。

### 11.1 在本机部署（最常用）

**1) 装 Docker Desktop** —— 按 [§1](#1-安装-docker) 装好并启动（这一步还是得你自己点鼠标装，CC 没法替你装桌面软件）。

**2) 装 Claude Code**

```bash
# macOS / Linux（任选其一）
curl -fsSL https://claude.ai/install.sh | bash
# 或用 npm（需先有 Node.js）
npm install -g @anthropic-ai/claude-code
```

Windows 在 PowerShell：`irm https://claude.ai/install.ps1 | iex`（或同样用 npm）。装完终端里输 `claude` 启动，首次会让你登录。

**3) 进到放文档的目录，启动 CC**

```bash
cd ~/dating-workspace   # 这份 local-infra-setup.md 所在的目录
claude
```

**4) 把活儿交给它** —— 在 CC 里直接说（把文件名换成你实际的）：

```
请阅读 local-infra-setup.md，在我这台机器上用 Docker 部署其中第 1–9 节的全部基础设施
（PG / Redis / MinIO / Nacos / RocketMQ）。逐步执行命令，每个组件起来后做文档里的健康检查，
不通就自己排查修复，全部 Up 且 healthy 后给我一份结果汇总。
```

想连 IM / 音视频也一起装，就再补一句：

```
另外把第 10 节可选的 OpenIM + LiveKit 也部署了，并按 10.8 把 openim-electron-demo 跑起来，
最后告诉我打开哪个网址、怎么注册两个账号测消息和语音。
```

**5) 过程中你要做的事**：CC 每跑一条命令会**请求权限**，看一眼没问题就批准；它会自己 `docker compose up`、看日志、改配置、重试。卡住时它会问你，照实回答即可。

> 小提示：
> - 可选的第 10 节会让 CC 从 OpenIM 官方仓库取配置（§10.2），它会自己 clone、按 §10.3 改成本机地址，你不用管细节。
> - 嫌每条命令都要点同意太烦，可以启动时用 `claude --dangerously-skip-permissions` 让它免确认连续跑——**只在你信任的本机环境用**，它会无提示地执行命令。

### 11.2 有服务器？用 sshpass 让 CC 直接操作远端

如果你有一台云服务器（已装好 Docker），可以让 CC 通过 `sshpass` 用「IP + 密码」直接登上去部署，你不用自己敲 ssh。

**1) 本机装 sshpass**

```bash
# macOS（core 里没有，走第三方 tap）
brew install hudochenkov/sshpass/sshpass
# Debian / Ubuntu
sudo apt-get install -y sshpass
# CentOS / RHEL
sudo yum install -y sshpass
```

**2) 在本机目录启动 CC，把服务器信息给它**

```
我有台服务器，IP 1.2.3.4，root 密码 abc123（已装好 Docker）。
请用 sshpass 通过 ssh 连上去，按 local-infra-setup.md 第 1–9 节在那台服务器上部署全部基础设施，
每步做健康检查，不通自己修，完成后汇总每个服务的容器名和端口。
```

CC 会用类似 `sshpass -p 'abc123' ssh -o StrictHostKeyChecking=no root@1.2.3.4 '<命令>'` 的方式远程执行；你照例在它请求权限时把关即可。

> ⚠️ **安全提醒（很重要）**：
> - 用 IP + 密码是图省事，但密码会以明文出现在命令里。**只对你自己有权限的服务器这么做**，别拿来碰公司生产机或别人的机器。
> - 别把含密码的对话 / 文件提交到 git。
> - 部署完建议尽快把服务器密码改强、或改成 SSH 密钥登录（可以顺手让 CC 帮你配公钥免密，之后就不用 sshpass 了）。
> - 服务器上别用 `--dangerously-skip-permissions`，远端误操作不好回滚；老老实实一条条看着批。
