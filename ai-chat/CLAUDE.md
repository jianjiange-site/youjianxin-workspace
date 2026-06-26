# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在本仓库中工作时提供指导。

## 项目概述

一个基于 LangChain/LangGraph 的聊天 Agent，在 "Vibe" 交友 App 上模拟交友人设。提供两种运行模式：

- **gRPC 服务**（生产）：`python -m server`（`server/` 启动层），单进程承载 `ChatAgent.Chat` 与 `VisionAgent`（图像理解 / 颜值打分 / 头像分析）RPC
- **Chainlit Web 演示**（本地调试）：`main.py`，使用硬编码 Mock 用户数据

LLM 后端：DeepSeek（`deepseek-v4-flash`）。

## 项目中的约定

- 系统中真实用户：BioHuman 简称 BH
- 系统中的虚拟人：DigitalHuman 简称 DH
- 一般 from_user_id 为 BH，to_user_id 为 DH
- DH 通过 AI 生成针对 BH 的聊天回复
- thread_id 格式：`{min(bh_id, dh_id)}_{max(bh_id, dh_id)}`，同一 BH-DH 对永久复用
- **Prompt Cache 优化**：设计 prompt 时，将动态变量放在 prompt 末尾，静态内容放在前面。这样每次请求只有末尾变量部分 cache miss，前缀部分（角色设定、规则等）全部命中 cache。`system.md` 已遵循此规则——Identity Foundation（含 `{{DH_USER_INFO}}` / `{{BH_USER_INFO}}`）放在 prompt 最末尾

## 常用命令

```bash
uv sync                                                    # 安装依赖
chainlit run main.py                                       # 启动 Chainlit 演示 UI（http://localhost:8000）
chainlit run main.py --headless                            # 无头模式
python -m server                                          # 启动 gRPC server（需 PG + UserService）
```

## 架构

### 两种运行路径

```
┌─ 生产路径（gRPC）──────────────────────────────────────────────────┐
│                                                                     │
│  gRPC Client ──► grpc_server.py ──► UserServiceClient (gRPC)       │
│                       │                   │                         │
│                       │                   └─► user-service
│                       │                       获取 BH/DH 用户资料    │
│                       │                                             │
│                       └─► agent.py                                  │
│                            build_agent(checkpointer=PostgresSaver)  │
│                              └─ @dynamic_prompt middleware          │
│                              └─ SummarizationMiddleware             │
│                              └─ create_agent()                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

┌─ 本地调试路径（Chainlit）───────────────────────────────────────────┐
│                                                                     │
│  Browser ──► main.py ──► build_agent(checkpointer=InMemorySaver)   │
│                             └─ Mock BH/DH 数据                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### `main.py` — Chainlit 入口

`@cl.on_message` 处理器。使用 `build_agent()` 创建 agent，通过 `UserContext(bh_info, dh_info)` 注入 Mock 用户数据。仅用作本地 prompt 调试。

### `chat_agent/builder.py` — Agent 工厂

通过 `build_agent(llm, checkpointer)` 创建单一 agent，内部使用：

- **LLM**：由 `core/llm.py` 的 `build_chat_llm()` 构造（`ChatDeepSeek(model="deepseek-v4-flash")`，thinking 关闭，temperature 0.7），由调用方注入
- **API key**：从 `DEEPSEEK_API_KEY` 环境变量读取（`SecretStr`）
- **@dynamic_prompt middleware**：每次请求从 `Runtime.context` 读取 `UserContext`，动态调用 `build_system_prompt(from_user=dh, to_user=bh)` 注入 system prompt。这使得所有会话可以共用同一个 agent 实例
- **SummarizationMiddleware**：消息超过 200 条触发摘要，保留最近 40 条，摘要 prompt 来自 `chat_agent/prompts/dating_summary_prompt.md`
- **Checkpointer**：由调用方传入（gRPC 用 `AsyncPostgresSaver`，Chainlit 用 `InMemorySaver`）
- **Tools**：空列表（尚未接入工具）
- **context_schema**：`UserContext`，包含 `bh_info` 和 `dh_info` 两个 `UserInfo`

### `chat_agent/prompts/system.md`

System prompt 模板，定义女性交友 App 人设，包含轮次对话规则（1–5 轮破冰，6–20 轮升温，21+ 轮熟络）、语气动态表、语言风格规则，以及邀约/性内容/反机器人话术的处理方式。

使用 `{{DH_USER_INFO}}` 和 `{{BH_USER_INFO}}` 占位符，由 `chat_agent/prompts/utils.py` 中的 `build_system_prompt()` 替换。

### `chat_agent/prompts/utils.py`

- `build_system_prompt(from_user: UserInfo, to_user: UserInfo) -> str` — 读取 `system.md` 模板，替换占位符。`from_user` = DH（AI 人设），`to_user` = BH（对话对象）
- `format_user_info(user: UserInfo) -> str` — 格式化用户信息为文本
- `load_dating_summary_prompt() -> str` — 读取 SummarizationMiddleware 用的摘要 prompt

### `chat_agent/prompts/dating_summary_prompt.md`

SummarizationMiddleware 的摘要 prompt 模板。摘要格式为三段：DH 说过的、BH 透露的、对话进度。

### `server/` — gRPC 启动层

入口 `python -m server`（`server/__main__.py`）：`core.config.Settings.from_env()` 读取 env、（可选）初始化 Nacos、`core.llm.build_chat_llm()` 构建 LLM，调用 `server/bootstrap.py` 的 `start_server()`。

`start_server()` 在**单进程**内用 `AsyncConnectionPool` + `AsyncPostgresSaver` 管理 PG 连接，同时注册 `ChatAgentServicer`（`chat_agent/servicer.py`）与 `VisionAgentServicer`（`vision_agent/servicer.py`）两个 servicer，开启 gRPC 反射，注册 SIGINT/SIGTERM 信号实现优雅停机。

`ChatAgentServicer.Chat()` 处理 `ChatAgent.Chat` RPC：校验 `thread_id` / `from_user_id` / `to_user_id` → `UserServiceClient.get_bh_and_dh_users()` 并行查 BH/DH 资料 → `agent.ainvoke()` 注入 `UserContext(bh_info, dh_info)` → 返回 `ChatResponse`。

### `core/clients/user_client.py` — UserService gRPC 客户端

封装对 `user/UserService` 的调用：

- `get_user_info(user_id)` — 查询单个用户，proto → `UserInfo` dict
- `get_bh_and_dh_users(bh_id, dh_id)` — 并行查询两个用户
- 自动管理 channel 生命周期，超时 5s，错误统一转为 `UserServiceError`

### `core/models.py`

```python
class UserInfo(TypedDict):
    name: str
    age: int
    gender: str
    city: str
    bio: str
```

### `core/tools/`

`core/tools/weather.py` — mock `get_weather(city)`，返回硬编码字符串。未接入 agent。

### `core/llm.py` / `core/config.py`

- `build_chat_llm()` — DH 聊天用 DeepSeek；`build_vision_models()` — 懒加载 groq + gemini 多模态列表（顺序即 SmartRouter prefer_first 优先级）
- `Settings.from_env()` — 集中读取 PG / Nacos / gRPC / UserService 配置（替代原 grpc_server.__main__ 的散读）

### `vision_agent/` — 图像 agent

`build_understand_agent` / `build_score_agent` / `build_profile_agent` 复用 `_build_vision_agent(system_md)`：`create_agent(model=groq, middleware=[SmartRouterMiddleware([groq, gemini], "round_robin")], system_prompt=…, checkpointer=None)`，无状态单轮，请求在 groq / gemini 间轮询，出错模型熔断跳过。

`VisionAgentServicer` 三个 RPC：
- `Understand(image_urls, prompt)` — 图像理解，返回文本
- `ScoreFace(image_urls)` — 颜值打分，返回 `status` / `appearance` / `sexual_attractiveness_score`（0–100，多图平均；**不含种族维度**）
- `AnalyzeProfilePhoto(image_url)` — 头像分析，返回 `{"analysis": "描述 | 标签" / not_human / anime_human}`

prompt 在 `vision_agent/prompts/`：`understand_system.md` / `face_score_system.md` / `profile_photo_system.md`。

### `core/middlewares/smart_router.py` — 多模型路由中间件

`SmartRouterMiddleware(models, strategy)`：`prefer_first`（首选降级）/ `round_robin`（轮询）+ 连续错误熔断冷却。vision_agent 用它在 groq / gemini 间路由回退。

## Proto 仓库

Proto 定义在**同一 workspace 的 `../proto/`**(youjianxin-workspace monorepo),是所有微服务(Java / Python)的协议唯一源头。本服务用到 `chat` / `vision` / `user` 三个模块。

### 多语言共用流程

```
youjianxin-workspace/proto/
  ├── chat/chat.proto · vision/vision.proto · user/user.proto
  ├── <svc>/pom.xml              → Java Maven 模块(com.dating.youjianxin.proto:*)
  └── <svc>/python/              → Python 包 (dating-proto-youjianxin-<svc>)
         │  改 .proto 后:  ../proto/gen-python.sh 重新生成 stub
         ▼  python -m build + twine upload → Nexus pypi-hosted
         ▼  pyproject 经 Nexus pypi-group 索引: dating-proto-youjianxin-chat==<version>
```

- Python 侧用 `uv` 管理,`pyproject.toml` 配 Nexus 索引(`pypi-group`)并声明 `dating-proto-youjianxin-*` 依赖
- 包坐标必带 `youjianxin` 前缀(仓库根 CLAUDE.md 红线 #7);改 proto 必须升版本号再发,同版本 Nexus 只能发一次
- 生成物已入库(`../proto/<svc>/python/dating_proto_youjianxin_<svc>/`),发布详见 `../proto/gen-python.sh` 头注释

### 本项目用到的 Service

- **`chat/ChatAgent`** — 本项目对外提供的 gRPC 接口（`Chat`）
- **`vision/VisionAgent`** — 本项目对外提供的图像 gRPC 接口（`Understand` / `ScoreFace` / `AnalyzeProfilePhoto`）
- **`user/UserService.GetUserInfo`** — 查询用户资料，用于组装 system prompt

## 环境与配置

环境变量文件（均包含 API key）：
- `.env` — 由 python-dotenv 加载，不入库
- `env.example` — 模板文件，已入库；复制为 `.env` 后由 python-dotenv 加载

关键变量：`DEEPSEEK_API_KEY`、`LANGSMITH_TRACING`、`LANGSMITH_API_KEY`、`LANGSMITH_PROJECT`、`USER_SERVICE_ADDR`、`GRPC_LISTEN_ADDR`、`PG_HOST`、`PG_PORT`、`PG_DB`、`PG_USER`、`PG_PASSWORD`。

Chainlit 配置在 `.chainlit/config.toml`。

## LangChain API 约定

本项目使用 **LangChain 统一 API**，以 `docs.langchain.com` 官网为准：
- Agent 创建：`from langchain.agents import create_agent`（返回 `CompiledStateGraph`，内部即 LangGraph）
- `create_agent` 参数签名以 `.venv/lib/python3.13/site-packages/langchain/agents/factory.py` 为准
- **写 agent 相关代码前，先读 `factory.py` 确认 API 签名**

本项目的关键 LangChain 用法：

- `@dynamic_prompt` middleware — 在每次 model 调用前动态生成 system prompt，从 `request.runtime.context` 读取 `UserContext`
- `SummarizationMiddleware` — 消息超阈值时自动触发 LLM 摘要，keep 参数控制保留最近消息数
- `context_schema` — `create_agent()` 参数，声明 runtime context 的 Pydantic 模型
- `agent.ainvoke(input, config, context=...)` — 注入 runtime context

`create_agent` 内部构建的图结构：

```
create_agent()
  └── StateGraph ├── Node: "model" → 调 LLM
                  ├── Node: "tools" → 执行工具
                  └── Edge: START → model ↔ tools → END
```

需要自定义节点/边（如自定义 Summarization）时，才下沉到 `StateGraph` + `add_node` + `add_edge`。

## 关键设计决策

- **共享 checkpointer + 动态 prompt**：所有会话共用同一个 agent 实例和 checkpointer，通过 `thread_id` 隔离对话历史，通过 `@dynamic_prompt` + `context=` 动态注入每对 BH/DH 的 system prompt
- **PostgreSQL 持久化**：gRPC 路径使用 `AsyncPostgresSaver`，重启后对话历史保留。Chainlit 本地调试仍用 `InMemorySaver`
- **SummarizationMiddleware**：消息超过 200 条自动触发摘要压缩，防止 token 成本线性增长。由 LangChain 内置 middleware 处理，无需手写 StateGraph
- **UserService 并行查询**：BH 和 DH 资料通过 `asyncio.gather` 并行获取

## 依赖

- **langchain** / **langgraph** — agent 框架 + 状态图 / checkpointing
- **langchain-deepseek** — DeepSeek LLM（兼容 OpenAI API）
- **chainlit** — Web 聊天 UI（仅本地调试）
- **langsmith** — 可观测性/追踪（可选，由环境变量控制）
- **grpcio** — gRPC 服务端
- **dating-proto-youjianxin-chat** / **dating-proto-youjianxin-user** — proto stub，通过 `uv` + Nexus 私服管理
- **langgraph-checkpoint-postgres** — PostgreSQL checkpoint 持久化
- **psycopg** / **psycopg-pool** — PG 异步连接池
