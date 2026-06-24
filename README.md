# youjianxin-workspace

学员个人 workspace,所有自研服务通过 workspace 根目录的 `deploy/docker-compose.dev.yml` 一键起,中间件(PG / Redis / Nacos / MinIO / RocketMQ)统一连远端共享基建(`38.76.188.242` 那台教学机)。

> 工作指引和命名规范看 [`CLAUDE.md`](CLAUDE.md);完整业务设计看 [`docs/post-service-design.md`](docs/post-service-design.md)。

---

## 一、快速开始(clone 完照着走)

### 1. 前置

| 工具 | 怎么验 |
|---|---|
| Docker(含 Compose v2)| `docker compose version` |
| Git | `git --version` |
| 网络能访问 `38.76.188.242` + `*.jianjiange.site` | 浏览器开 [http://38.76.188.242:8848/nacos](http://38.76.188.242:8848/nacos) 能见登录页 |

### 2. Clone + 准备本地环境

```bash
git clone git@github.com:jianjiange-site/youjianxin-workspace.git
cd youjianxin-workspace

# 容器跑在外部 docker 网络 dating-app(只建一次)
docker network create dating-app

# 本机环境变量(整个项目只需要这一个,其他全部从 Nacos 拉)
cp deploy/.env.deploy.example deploy/.env.deploy
# 编辑 deploy/.env.deploy,把 NACOS_PASSWORD= 后面填 jianjiange
# (.env.deploy 已被 .gitignore 排掉,不会误提)
```

### 3. 共享基建一次性配置(每个学员只做一次)

学员通过命名前缀在共享基建上互不打架。本仓库统一用 `youjianxin-dating-dev`(其他人换成自己的 `<拼音>-dating-dev`,详见 [`CLAUDE.md §个人隔离前缀`](CLAUDE.md))。

凭据从 [`docs/dev-onboarding.md §0`](docs/dev-onboarding.md) 速查表取。

#### 3.1 Nacos:建 namespace + 贴配置

1. 浏览器开 [http://38.76.188.242:8848/nacos](http://38.76.188.242:8848/nacos),用 `nacos` / `jianjiange` 登录
2. 左侧「命名空间 → 新建命名空间」:命名空间名 + ID 都填 **`youjianxin-dating-dev`**
3. 切到该 namespace,「配置管理 → 新建配置」:
   - **Data ID**:`post-service.yaml`(字字相同)
   - **Group**:`DEFAULT_GROUP`
   - **格式**:`YAML`
   - **内容**:打开本仓库 [`nacos/post-service.yaml`](nacos/post-service.yaml),**整文件粘贴**
4. 「发布」

#### 3.2 PostgreSQL:建库

```bash
# 装了 psql:
PGPASSWORD='MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V' \
  psql -h 38.76.188.242 -p 5433 -U jianjian_test -d postgres \
  -c 'CREATE DATABASE "youjianxin-dating-dev";'
# ⚠️ dash 库名 SQL 内必须双引号
```

没装 psql 用 DataGrip(`dev-onboarding §2.2`)。表结构启动时 Flyway 自动跑。

#### 3.3 MinIO:建 bucket

[https://minio.jianjiange.site](https://minio.jianjiange.site) 用 `admin` 登录 → 「Buckets → Create Bucket」→ 填 `youjianxin-dating-dev`。

#### 3.4 RocketMQ:建 topic

[https://rocketmq.jianjiange.site](https://rocketmq.jianjiange.site) 用 `student` / `MwTt14eUL9s1M3` 登录 → 「Topic → Add/Update」:

- Topic 名:`youjianxin-dating-dev-post-fanout-v1`
- 其他选项默认

> Redis 不用建任何资源 —— key 前缀启动后自然生效。

### 4. 起服务

```bash
docker compose -f deploy/docker-compose.dev.yml up -d --build
docker compose -f deploy/docker-compose.dev.yml logs -f
```

日志看到 `Tomcat started on port 8080` 和 `gRPC Server started ... port: 9090` 就 OK。

### 5. 验证

```bash
# 健康检查
curl http://localhost:8080/actuator/health
# {"status":"UP"}

# 发个帖子(需要 grpcurl:brew install grpcurl)
grpcurl -plaintext \
  -H 'x-user-id: 1001' \
  -d '{"content":"hello dating world","image_keys":[]}' \
  localhost:9090 post.PostService/CreatePost
# 返回 post_id
```

副作用验证(选做):

| 组件 | 看什么 | 怎么看 |
|---|---|---|
| PG | `posts` 表多 1 行 | DataGrip 连 `youjianxin-dating-dev` 库 |
| Redis | `youjianxin-dating-dev:post:detail:<id>` 命中 | `redis-cli -h 38.76.188.242 -p 6380 -a '<密码>' -n 1` 后 `KEYS youjianxin-dating-dev:*` |
| RocketMQ | topic 多 1 条 msg | rocketmq.jianjiange.site → Message |

---

## 二、目录结构

```
youjianxin-workspace/
├── README.md                       # 本文件
├── CLAUDE.md                       # 工作指引 + 隔离命名规范 + 红线
├── deploy/                         # 所有自研服务的 docker compose(workspace 唯一一份)
│   ├── docker-compose.dev.yml
│   └── .env.deploy.example
├── nacos/                          # Nacos 粘贴模板,Data ID = <service>.yaml
│   ├── README.md
│   └── post-service.yaml
├── docs/                           # 设计文档 / 规范 / 速查表
│   ├── dev-onboarding.md
│   ├── student-dev-guide.md
│   ├── local-infra-setup.md
│   └── post-service-design.md
├── proto/                          # gRPC 接口定义(发到 Nexus)
└── dating-server/
    └── post-service/               # 帖子服务实现
        ├── Dockerfile
        └── src/
```

---

## 三、加新服务怎么扩

未来要把 `user-service` / `im-service` / `match-service` 等也搬进来:

1. 在 `dating-server/` 下新建 `<service>/`,带 `pom.xml` + `Dockerfile`(参照 post-service)
2. 在 `nacos/<service>.yaml` 放配置模板,贴到 Nacos namespace `youjianxin-dating-dev`,Data ID 同名
3. 在 `deploy/docker-compose.dev.yml` 的 `services:` 下追加一段:
   ```yaml
     <service>:
       container_name: youjianxin-dating-dev-<service>
       build:
         context: ../dating-server/<service>
         dockerfile: Dockerfile
       image: youjianxin-dating-dev-<service>:dev
       env_file:
         - .env.deploy
       environment:
         SPRING_PROFILES_ACTIVE: dev
         TZ: UTC
       ports:
         - "<host-port>:<container-port>"
   ```
4. `docker compose -f deploy/docker-compose.dev.yml up -d --build <service>`

中间件仍走远端共享,本机不再加 PG / Redis 容器。

---

## 四、常见问题

| 现象 | 处理 |
|---|---|
| `Nacos config not found, data id=post-service.yaml` | §3.1 没做或 Data ID 拼错。一字一符:`post-service.yaml`,namespace `youjianxin-dating-dev` |
| `Flyway migration failed: relation already exists` | PG 库有旧表。`DROP DATABASE "youjianxin-dating-dev";` 重建 |
| `NOAUTH Authentication required.`(Redis) | Nacos 里 Redis 密码错。核对 `dev-onboarding §0` |
| `Could not find artifact com.dating.youjianxin.proto:post-proto` | `~/.m2/settings.xml` 没配 Nexus 账号(`dev-onboarding §8.1`) |
| RocketMQ `topic not exist` | §3.4 没建 topic |
| RocketMQ `ACL ... authentication failed` | Nacos 里 RocketMQ AK/SK 错;AK 是 `rocketmq-student`,SK 见 `dev-onboarding §6.1` |
| docker compose 报 `network dating-app not found` | `docker network create dating-app` |
| 端口冲突 8080 / 9090 | `lsof -i :8080` 看占用进程 |

更全的排障见 [`docs/dev-onboarding.md §10`](docs/dev-onboarding.md) 和 [`docs/post-service-design.md §13 失败模式`](docs/post-service-design.md)。

---

## 五、Git 工作流

- 个人分支命名:`youjianxin/<topic>`,例 `youjianxin/post-comment-tree`
- Commit message:Conventional Commits,`<type>(<service>): <概要>`
- PR:个人分支 → `dev`(`main` 受保护;本仓库目前没建 `dev`,直接 PR 到 `main` 也行)
