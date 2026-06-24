# youjianxin-workspace

学员个人 workspace 仓库，对应 `jianjiange-site` 组织下 dating 后端项目的个人开发空间。
未来会拆分为 `ai-chat` / `dating-server` / `proto` 三个目录，目前还在起步阶段。

## 个人隔离前缀

所有共享基建资源按 **`<拼音>-dating-<env>`** 模式统一隔离,本仓库 dev 环境用 **`youjianxin-dating-dev`**。命名规则:

- **全部 dash 分隔**,不用下划线(包括 RocketMQ topic / PG 库名,带 dash 的 PG 库名 SQL 中需要 `"双引号"` 包围)
- **物理资源都带 `<pinyin>-dating-<env>`** —— PG / Redis 前缀 / MinIO / RocketMQ / docker 容器名 / OpenIM userID
- **逻辑标识不带前缀** —— Spring `application.name`、Nacos Data ID 都是纯 `<service>` 名;**学员/env 隔离全靠 Nacos namespace 这一层兜底**

| 资源 | 取值 | 备注 |
|---|---|---|
| PG 库 | `youjianxin-dating-dev` | SQL 内带 dash 必须引号:`CREATE DATABASE "youjianxin-dating-dev";` |
| Redis key 前缀 | `youjianxin-dating-dev:<service>:<domain>:<id>` | |
| Nacos namespace | `youjianxin-dating-dev` / `youjianxin-dating-prod` | 学员 × env 都靠这层 |
| Nacos Data ID | `<service>.yaml` 例 `post-service.yaml` | **不带前缀**,namespace 已隔离 |
| MinIO bucket | `youjianxin-dating-dev` | |
| RocketMQ topic / group | `youjianxin-dating-dev-*` | 例 `youjianxin-dating-dev-post-fanout-v1` |
| Spring `application.name` | `<service>` 例 `post-service` | **不带前缀**;服务发现靠 namespace 隔离 |
| docker 容器 / image | `youjianxin-dating-dev-<service>` | 物理 docker host 共享时防撞 |
| docker compose 项目名 | `youjianxin-dating-dev` | |
| OpenIM userID | `youjianxin-dating-dev-<service>-<id>` | |
| Proto 包坐标 | `com.dating.youjianxin.proto:*` / `dating-proto-youjianxin-*` | **历史包名保留**(已发到 Nexus),不跟随新规范 |

prod 环境把所有 `-dev` 换成 `-prod` 即可。其他拼音的学员把 `youjianxin` 换成自己的拼音。

> ⚠️ `docs/dev-onboarding.md` 里的示例仍用旧版占位 `dating-<yourname>` / `dev_<yourname>_*`,**不要按字面替换**,以本表为准。

## 关联文档（权威，先读这些）

- [`docs/student-dev-guide.md`](docs/student-dev-guide.md) — 后端开发总规范，技术栈、Git、目录、红线全在这里
- [`docs/dev-onboarding.md`](docs/dev-onboarding.md) — 接入共享基建（远端 `38.76.188.242`）的连接配置 + 凭据
- [`docs/local-infra-setup.md`](docs/local-infra-setup.md) — 在本机用 Docker 起一套自用中间件
- [`docs/post-service-design.md`](docs/post-service-design.md) — post-service 业务设计草案

## 硬约束（违反一票否决）

来自 `student-dev-guide §10 红线`，写代码前先对一遍：

1. **生产环境**密码 / AK SK / Token **绝不进 git**。学员**共享 dev 凭据**(`38.76.188.242` 那套)为方便快速上手,允许写入 workspace `nacos/<service>-<env>.yaml` 配置模板和 `docs/dev-onboarding.md` 速查表;**业务代码 / `application*.yml` / `.env*` 等运行时配置仍走 `${ENV}` 占位**,真值放 Nacos 或环境变量。
2. 持久层 **禁多表 JOIN**，跨表在 service 层多次单表查 + 内存拼装。
3. 服务间 **禁 HTTP 互调**，只用 gRPC；REST 仅对外（App / H5 / 第三方）。
4. 跨服务 **禁直连别人家的 DB / Redis key / 对象桶**，要数据就调对方 gRPC。
5. **时间一律 UTC**：DB 列 `TIMESTAMPTZ`、JVM `TZ=UTC`、连接 session `SET TIME ZONE 'UTC'`。
6. **业务服务不直连 OpenIM / LiveKit**，IM / 音视频能力统一经 `im-service` gRPC。
7. **`.proto` 走 Nexus 包**：proto 文件放 `proto/`，发布坐标必须带 `youjianxin` 前缀。
8. **不要新增中间件**（ES / Mongo / ZK 等)未经评审。RocketMQ 已纳入基础组件清单,可直接使用。

## 技术栈

- Java 21 / Spring Boot 3.3.5 / MyBatis-Plus
- PostgreSQL 16（唯一关系库，禁 MySQL）
- Redis 7 单实例（必带 TTL + 学员前缀）
- MinIO（S3 兼容，统一经 `dating-common` 的 `ObjectStorage` 接口）
- Nacos 2.4（Config + Discovery 同 namespace）
- RocketMQ 5.3.1（异步事件 / 写扩散 / 解耦,公网客户端必带 AK/SK,见 `dev-onboarding §6`）
- gRPC 1.68.1 + Protobuf 4.28.3
- Python ≥ 3.13（ai-chat 部分）

## Git 工作流

- `master` 受保护，只接 `dev → master` 的 PR
- `dev` 集成主线，个人分支 PR 合入
- 个人分支命名：`youjianxin/<topic>`，例 `youjianxin/post-feed-rank`
- Commit message 走 Conventional Commits：`<type>(<service>): <概要>`

## 协作偏好

- 回答简洁，直接给结论 / diff，不要长段铺垫总结。
- 涉及连接配置、密码、隔离前缀时，**先核对 `docs/` 里的权威文档**，不要凭记忆生成。
- 触发文档里写明的红线时，必须明确指出并拒绝，不要默默"兼容"实现。
