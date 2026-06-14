# youjianxin-workspace

学员个人 workspace 仓库，对应 `jianjiange-site` 组织下 dating 后端项目的个人开发空间。
未来会拆分为 `ai-chat` / `dating-server` / `proto` 三个目录，目前还在起步阶段。

## 个人隔离前缀

所有共享基建资源都按拼音前缀隔离，本仓库统一用 **`youjianxin`**：

| 资源 | 取值 |
|---|---|
| PG 库 | `dating-dev-youjianxin` |
| Redis key 前缀 | `youjianxin:<service>:<domain>:<id>` |
| Nacos namespace | `dev-youjianxin` |
| MinIO bucket | `dating-youjianxin` |
| RocketMQ topic / group | `dev_youjianxin_*` |
| Proto 包坐标 | `com.dating.youjianxin.proto:*` / `dating-proto-youjianxin-*` |
| OpenIM userID | `<service>_youjianxin_*` |

文档里凡是写 `<name>` / `<yourname>` / `<yourpinyin>` 的地方，都替换成 `youjianxin`。

## 关联文档（权威，先读这些）

- [`docs/student-dev-guide.md`](docs/student-dev-guide.md) — 后端开发总规范，技术栈、Git、目录、红线全在这里
- [`docs/dev-onboarding.md`](docs/dev-onboarding.md) — 接入共享基建（远端 `38.76.188.242`）的连接配置 + 凭据
- [`docs/local-infra-setup.md`](docs/local-infra-setup.md) — 在本机用 Docker 起一套自用中间件
- [`docs/post-service-design.md`](docs/post-service-design.md) — post-service 业务设计草案

## 硬约束（违反一票否决）

来自 `student-dev-guide §10 红线`，写代码前先对一遍：

1. 真实密码 / AK SK / Token **绝不进 git**，先填占位跑通，再挪到 Nacos / 环境变量。
2. 持久层 **禁多表 JOIN**，跨表在 service 层多次单表查 + 内存拼装。
3. 服务间 **禁 HTTP 互调**，只用 gRPC；REST 仅对外（App / H5 / 第三方）。
4. 跨服务 **禁直连别人家的 DB / Redis key / 对象桶**，要数据就调对方 gRPC。
5. **时间一律 UTC**：DB 列 `TIMESTAMPTZ`、JVM `TZ=UTC`、连接 session `SET TIME ZONE 'UTC'`。
6. **业务服务不直连 OpenIM / LiveKit**，IM / 音视频能力统一经 `im-service` gRPC。
7. **`.proto` 走 Nexus 包**：proto 文件放 `proto/`，发布坐标必须带 `youjianxin` 前缀。
8. **不要新增中间件**（MQ / ES / Mongo / ZK 等）未经评审。

## 技术栈

- Java 21 / Spring Boot 3.3.5 / MyBatis-Plus
- PostgreSQL 16（唯一关系库，禁 MySQL）
- Redis 7 单实例（必带 TTL + 学员前缀）
- MinIO（S3 兼容，统一经 `dating-common` 的 `ObjectStorage` 接口）
- Nacos 2.4（Config + Discovery 同 namespace）
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
