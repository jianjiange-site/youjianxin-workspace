# youjianxin-workspace

学员个人 workspace,对应 `jianjiange-site` 组织下 dating 后端项目的开发空间。
本仓库当前实现 **post-service**(发帖 / 点赞 / 评论 / Feed 推荐),后续会拆出 `ai-chat` / `dating-server` / `proto` 多个目录。

> **本 README 的目标**:让你从 0 开始,把这套代码在本机跑起来,看到接口响应。
> 设计细节看 [`docs/post-service-design.md`](docs/post-service-design.md),开发规范看 [`CLAUDE.md`](CLAUDE.md) + [`docs/student-dev-guide.md`](docs/student-dev-guide.md)。

---

## 目录结构

```
youjianxin-workspace/
├── CLAUDE.md                       # 工作指引 + 隔离命名规范 + 红线
├── README.md                       # 本文件
├── docs/                           # 设计文档 / 规范 / 速查表
│   ├── student-dev-guide.md        # 后端总规范
│   ├── dev-onboarding.md           # 共享基建连接 + 凭据速查
│   ├── local-infra-setup.md        # 本机起一套自用中间件
│   └── post-service-design.md      # post-service 业务设计
├── nacos/                          # 直接贴到 Nacos 的配置模板
│   ├── README.md
│   └── youjianxin-dating-post-service-dev.yaml
├── proto/                          # gRPC 接口定义(发到 Nexus 后业务引用)
└── dating-server/
    └── post-service/               # 帖子服务实现
```

---

## 前置条件

| 工具 | 版本 | 怎么验 |
|---|---|---|
| JDK | 21+(Temurin 推荐) | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Docker | 任意近期版本(只在用 compose 跑时需要)| `docker version` |
| 网络 | 能访问 `38.76.188.242`(共享基建)+ `nexus.jianjiange.site`(Nexus)+ `*.jianjiange.site`(MinIO/RocketMQ 控制台)| ping 一下 |

> 凭据(Nacos / PG / Redis / MinIO / RocketMQ 各组件的密码)统一在 [`docs/dev-onboarding.md §0`](docs/dev-onboarding.md) 速查表里。下文步骤里会指你去看。

---

## 步骤总览

| 步骤 | 内容 | 每个学员频率 |
|---|---|---|
| 1 | 一次性基建配置(Nacos / PG / MinIO / RocketMQ) | **首次,只做一次** |
| 2 | `~/.m2/settings.xml` 配 Nexus 账号 | 每台开发机一次 |
| 3 | 跑 post-service(IDE 或 docker compose) | 日常 |
| 4 | 验证接口通了 | 每次重大改动后 |

---

## 步骤 1:一次性基建配置

按 [`CLAUDE.md §个人隔离前缀`](CLAUDE.md) 的命名规范,本仓库统一用 `youjianxin-dating` 这个学员前缀。如果你不是 youjianxin,把下面所有 `youjianxin` 替换成你的拼音,所有 `youjianxin-dating` 替换成 `<你的拼音>-dating`。

### 1.1 Nacos:建 namespace + 贴配置

1. 浏览器开 [http://38.76.188.242:8848/nacos](http://38.76.188.242:8848/nacos),账号 `nacos` / 密码 `jianjiange`(见 `dev-onboarding §0`)
2. 左侧「命名空间 → 新建命名空间」:
   - 命名空间名:`youjianxin-dating-dev`
   - 命名空间 ID:同名 `youjianxin-dating-dev`(手填,方便后面对照)
3. 切到刚建的 namespace,「配置管理 → 新建配置」:
   - **Data ID**:`youjianxin-dating-post-service-dev.yaml`(必须字字符不差)
   - **Group**:`DEFAULT_GROUP`(默认)
   - **配置格式**:`YAML`
   - **配置内容**:打开本仓库 [`nacos/youjianxin-dating-post-service-dev.yaml`](nacos/youjianxin-dating-post-service-dev.yaml),**整文件复制粘贴**
4. 点「发布」保存

后续要改连接信息(比如换 Redis 密码、加新配置项),**只改 Nacos 控制台,不动 git**。

### 1.2 PostgreSQL:建库

```bash
PGPASSWORD='MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V' \
  psql -h 38.76.188.242 -p 5433 -U jianjian_test -d postgres \
  -c 'CREATE DATABASE youjianxin_dating;'
```

没装 psql 的可以用 DataGrip(见 `dev-onboarding §2.2`)。

**表结构不用建** —— 服务启动时 Flyway 自动跑 `db/migration/V20260615_01__init_post_tables.sql`,5 张业务表 + `shedlock` 表都会自动出来。

### 1.3 MinIO:建 bucket

```bash
# 装了 mc 客户端的:
mc alias set dev https://minio-api.jianjiange.site admin 'GorLDkuOhGyK5c1RXh2gaPooXgtso/MR'
mc mb dev/youjianxin-dating
```

或者上 [https://minio.jianjiange.site](https://minio.jianjiange.site)(`admin` / 见 `dev-onboarding §0`)→ Buckets → Create Bucket → 名字填 `youjianxin-dating`。

### 1.4 RocketMQ:建 topic

1. 浏览器开 [https://rocketmq.jianjiange.site](https://rocketmq.jianjiange.site),Basic Auth `student` / `MwTt14eUL9s1M3`(见 `dev-onboarding §6.1`)
2. 「Topic → Add/Update」:
   - Topic 名:`youjianxin_dating_post_fanout_v1`
   - clusterName / brokerName:默认勾选
   - readQueueNums / writeQueueNums:默认 8
3. 「OK」保存

> Redis 不用建任何资源 —— key 前缀启动后自然生效;db 号沿用 dev-onboarding 约定的 `1`。

---

## 步骤 2:Nexus 拉 proto 包

post-service 依赖 `com.dating.youjianxin.proto:post-proto:0.1.0`,从 Nexus 拉。在 `~/.m2/settings.xml` 加(用户级配置,不进任何 git 仓库):

```xml
<settings>
  <servers>
    <server>
      <id>nexus</id>
      <username>NEXUS_USER</username>
      <password>NEXUS_PASS</password>
    </server>
  </servers>
</settings>
```

NEXUS_USER / NEXUS_PASS 找管理员要(`dev-onboarding §8.1`)。

如果你改了 proto 文件:

```bash
cd proto
mvn -q clean deploy   # 必须升 version,同版本号 Nexus 只能发一次
```

然后业务工程 `pom.xml` 的 `<dependency>` 升到新版本。

---

## 步骤 3:跑 post-service

### 方式 A:IDE(推荐用于开发)

1. IntelliJ 打开 `dating-server/post-service/pom.xml`,等 Maven import 完成
2. 找到 `PostApplication`,右键 → Modify Run Configuration
3. **Environment Variables** 加一行(整个服务**只需要这一个**环境变量,其他全部从 Nacos 拉):
   ```
   NACOS_PASSWORD=jianjiange
   ```
4. 点 Run

启动日志看到这两行就 OK:
```
Tomcat started on port 8080
gRPC Server started, listening on address: *, port: 9090
```

### 方式 B:Docker Compose

```bash
# 一次性:建 docker 网络(已建过跳过)
docker network create dating-app

cd dating-server/post-service
cp deploy/.env.deploy.example deploy/.env.deploy
# 编辑 deploy/.env.deploy,把 NACOS_PASSWORD= 后面填上 jianjiange
# (这个文件已被 .gitignore 排掉,不会误提)

docker compose -f deploy/docker-compose.dev.yml up -d --build
docker logs -f youjianxin-dating-post-service-dev
```

---

## 步骤 4:验证

```bash
# 4.1 健康检查
curl http://localhost:8080/actuator/health
# 期望:{"status":"UP"}

# 4.2 发个帖子(需要 grpcurl,brew install grpcurl 或 https://github.com/fullstorydev/grpcurl)
grpcurl -plaintext \
  -H 'x-user-id: 1001' \
  -d '{"content":"hello dating world","image_keys":[]}' \
  localhost:9090 post.PostService/CreatePost
# 期望:{"baseResponse":{"code":0},"postId":"..."}
```

各组件的副作用验证:

| 组件 | 看什么 | 怎么看 |
|---|---|---|
| **PG** | `youjianxin_dating.posts` 表有 1 行 | DataGrip / `psql -c "SELECT * FROM posts;"` |
| **Redis** | `youjianxin-dating:post:detail:<postId>` 命中 | `redis-cli -h 38.76.188.242 -p 6380 -a '<password>' -n 1` 后 `KEYS youjianxin-dating:*` |
| **RocketMQ** | Topic `youjianxin_dating_post_fanout_v1` 有 1 条 msg | 浏览器开 rocketmq.jianjiange.site → Message → 按 topic 搜 |
| **冷启动池** | `youjianxin-dating:feed:cold_start:pool:<gender>` ZSet 多了 1 条 | redis-cli `ZRANGE ... 0 -1 WITHSCORES` |

---

## 常见问题

| 现象 | 处理 |
|---|---|
| `Nacos config not found, data id=...` | 步骤 1.1 没做或 Data ID 拼错。必须 `youjianxin-dating-post-service-dev.yaml`,大小写敏感 |
| `Flyway migration failed: relation already exists` | PG 库有旧表。`DROP DATABASE youjianxin_dating;` 重建,或手动清表 |
| `NOAUTH Authentication required.`(Redis)| Nacos 配置里 Redis 密码错。核对 `dev-onboarding §0` 速查表 |
| `Could not find artifact com.dating.youjianxin.proto:post-proto` | 步骤 2 没做,或 settings.xml 账号错。`mvn -X` 看仓库地址是不是 `nexus.jianjiange.site` |
| RocketMQ 报 `topic not exist` | 步骤 1.4 没建 topic |
| RocketMQ 报 `ACL ... authentication failed` | Nacos 配置里 access-key / secret-key 错。AK = `rocketmq-student`,SK 见 `dev-onboarding §6.1` |
| docker compose 容器起来但拒绝连接 | 没用外部网络 `dating-app`。`docker network create dating-app` 后重试 |
| 服务起来但 gRPC 9090 端口连不上 | 检查防火墙;`lsof -i :9090` 看进程;如果跑在 docker,看 `docker-compose.dev.yml` 端口映射 |

更全的排障见 [`docs/dev-onboarding.md §10`](docs/dev-onboarding.md) 和 [`docs/post-service-design.md §13 失败模式`](docs/post-service-design.md)。

---

## 下一步

- **改代码 / 加功能**:`docs/post-service-design.md` 是技术设计,改之前先对一遍 [`CLAUDE.md §硬约束`](CLAUDE.md)
- **开个分支**:`git checkout -b youjianxin/<topic>`,例 `youjianxin/post-comment-tree`
- **commit**:Conventional Commits,`<type>(<service>): <概要>`
- **PR**:个人分支 → `dev`(master 受保护,只接 dev→master)

---

## License / 内部使用

本仓库为内部学员训练空间,不公开发布。共享 dev 凭据已经在 `nacos/` 和 `docs/dev-onboarding.md` 里(CLAUDE.md 红线 #1 已为方便开发放宽),**但生产凭据严禁进 git**。
