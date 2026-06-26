# Chat Agent

基于 LangChain/LangGraph 的多 Agent AI 服务，在 "Vibe" 交友 App 上提供拟人化聊天与图像分析能力。单个 gRPC 进程承载多个 servicer。

**名词约定：**
- **BH（BioHuman）** — 系统中真实用户
- **DH（DigitalHuman）** — 系统中的虚拟人，AI 以 DH 身份生成回复

## 功能特性

- **ChatAgent（DH 聊天）** — `ChatAgent.Chat` RPC，DH 身份生成针对 BH 的拟人化回复
  - 动态人设：system prompt 注入 DH/BH 双方资料
  - 多轮对话：LangGraph checkpointer（PostgreSQL）维护上下文
  - 意图识别：快路关键词 + 门控 LLM 分类，注入应对策略
  - 超长对话自动摘要压缩（SummarizationMiddleware）
- **VisionAgent（图像）** — 三个 RPC，经 SmartRouter 在 groq / gemini 多模态间路由并熔断回退
  - `Understand` — 图像理解（按 prompt 描述/分析）
  - `ScoreFace` — 颜值打分（`status` / `appearance` / `sexual_attractiveness_score`，0–100）
  - `AnalyzeProfilePhoto` — 头像/资料照分析（`描述 | 标签` / `not_human` / `anime_human`）
- **Chainlit Web 演示** — 本地调试聊天 UI
- **服务治理** — Nacos 服务注册/发现、gRPC reflection、优雅停机
- **LangSmith 集成** — 内置追踪和调试支持

## 技术栈

- **LangChain / LangGraph** — Agent 框架 + 状态管理和编排
- **DeepSeek**（`deepseek-v4-flash`）— ChatAgent LLM 后端
- **Groq + Gemini** — VisionAgent 多模态后端（`SmartRouterMiddleware` 路由 + 熔断）
- **gRPC** — 服务间通信；**Nacos** — 服务注册发现；**PostgreSQL** — 对话持久化
- **Chainlit** — 本地 Web UI；**LangSmith** — 可观测性

## 快速开始

### 1. 安装依赖

```bash
uv sync
```

### 2. 配置环境变量

复制 `env.example` 为 `.env` 并填写：

```bash
DEEPSEEK_API_KEY=...        # ChatAgent
GROQ_API_KEY=...            # VisionAgent
GOOGLE_API_KEY=...          # VisionAgent
PG_USER=... / PG_PASSWORD=...  # gRPC 持久化
# 可选：LANGSMITH_* / NACOS_* / GROQ_VISION_MODEL / GEMINI_VISION_MODEL
```

### 3. 本地运行 Chainlit 演示（仅 chat）

```bash
chainlit run main.py                # Web UI（http://localhost:8000）
chainlit run main.py --headless     # 无头模式
```

### 4. 运行 gRPC 服务（chat + vision）

```bash
python -m server                    # 需 PostgreSQL（+ 可选 UserService / Nacos）
```

> VisionAgent 为可选启用：缺 `dating-proto-youjianxin-vision` 依赖或缺 `GROQ_API_KEY`/`GOOGLE_API_KEY` 时自动降级为只跑 ChatAgent（见 `server/bootstrap.py` 的 `_register_vision`）。

## 项目结构

```
chat-agent/
├── main.py                      # Chainlit 本地调试入口（仅 chat）
├── chat_agent/                  # ChatAgent —— DH 聊天回复
│   ├── builder.py               # build_agent + UserContext + @dynamic_prompt
│   ├── servicer.py              # ChatAgentServicer（Chat RPC）
│   ├── intent_classifier.py     # 意图分类（快路 + 门控 LLM）
│   └── prompts/                 # system.md / dating_summary_prompt.md / intent_classification.md / utils.py
├── vision_agent/                # VisionAgent —— 图像理解 / 颜值打分 / 头像分析
│   ├── builder.py               # 三个 agent 工厂（SmartRouter[groq, gemini]）
│   ├── servicer.py              # VisionAgentServicer（3 个 RPC）+ JSON 解析
│   └── prompts/                 # understand_system.md / face_score_system.md / profile_photo_system.md
├── core/                        # 共享基础设施（与具体 agent 无关）
│   ├── models.py                # UserInfo
│   ├── config.py                # Settings.from_env（集中读取 env）
│   ├── llm.py                   # build_chat_llm() / build_vision_models()
│   ├── middlewares/smart_router.py  # 多模型路由 + 熔断
│   ├── clients/user_client.py   # UserService gRPC 客户端
│   ├── nacos_client/            # 服务注册 / 发现 / 配置
│   └── tools/weather.py         # mock 工具（预留）
├── server/                      # gRPC 启动层
│   ├── bootstrap.py             # start_server：注册多 servicer + PG + Nacos + 优雅停机
│   └── __main__.py              # `python -m server` 入口
├── docs/                        # 设计文档
├── CLAUDE.md                    # Claude Code 项目指引
├── pyproject.toml
└── README.md
```

## 核心概念

### System Prompt 组装（ChatAgent）

`chat_agent/prompts/utils.py` 的 `build_system_prompt(from_user, to_user, intent_context)` 读取 `chat_agent/prompts/system.md` 并替换占位符：

```
{{DH_USER_INFO}}   ←  DH 虚拟人 — "You are this person"（AI 扮演的人设）
{{BH_USER_INFO}}   ←  BH 真人   — "You are speaking to this person"（对话对象）
{{INTENT_CONTEXT}} ←  意图分类器渲染的应对策略（可空）
```

参数命名注意：`from_user` = DH（说话方），`to_user` = BH（接收方），与 gRPC `ChatRequest` 中的 from/to 含义相反。

### 多模型路由（VisionAgent）

`core/llm.build_vision_models()` 返回 `[groq, gemini]`，由 `SmartRouterMiddleware(models, strategy="round_robin")` 接管模型调用：请求在 groq / gemini 间轮询分发，连续出错的模型触发熔断冷却并自动跳过。三个 vision agent（understand / score / profile）共用该机制，单轮无状态（不挂 checkpointer）。

### 请求流程

```
Chat:    gRPC Chat → UserService 取 BH/DH 资料 → @dynamic_prompt(意图分类 + system prompt)
                   → agent.ainvoke(thread_id) → PostgreSQL checkpoint → ChatResponse
Vision:  gRPC Understand/ScoreFace/AnalyzeProfilePhoto → image_message(URLs) → SmartRouter(groq/gemini 轮询)
                   → (JSON 解析) → 结构化响应
```

## gRPC 接口

```protobuf
service ChatAgent {                       // proto/chat/chat.proto
  rpc Chat(ChatRequest) returns (ChatResponse);
}

service VisionAgent {                      // proto/vision/vision.proto
  rpc Understand(UnderstandRequest) returns (UnderstandResponse);
  rpc ScoreFace(ScoreFaceRequest) returns (ScoreFaceResponse);
  rpc AnalyzeProfilePhoto(AnalyzeProfilePhotoRequest) returns (AnalyzeProfilePhotoResponse);
}
```

### 启动服务

```bash
python -m server                # 本地（需 PostgreSQL + 可选 UserService/Nacos）
```

### 测试请求（gRPC reflection 已启用，无需指定 proto）

```bash
brew install grpcurl
grpcurl -plaintext '[::1]:50051' list      # 应列出 chat.ChatAgent 与 vision.VisionAgent

# ChatAgent
grpcurl -plaintext -d '{
  "thread_id": "1_2", "from_user_id": "1", "to_user_id": "2", "message": "你好，最近怎么样？"
}' '[::1]:50051' ChatAgent/Chat

# VisionAgent
grpcurl -plaintext -d '{"image_urls": ["https://.../photo.jpg"], "prompt": "describe this image"}' \
  '[::1]:50051' vision.VisionAgent/Understand
grpcurl -plaintext -d '{"image_urls": ["https://.../photo.jpg"]}' \
  '[::1]:50051' vision.VisionAgent/ScoreFace
grpcurl -plaintext -d '{"image_url": "https://.../avatar.jpg"}' \
  '[::1]:50051' vision.VisionAgent/AnalyzeProfilePhoto
```

## 如何新增一个 Agent

以新增 vision_agent 的实践为蓝本，新增一个 agent（如 `match_agent`）的步骤：

### 1. 在 proto 模块定义服务并发布

proto 在**同一 workspace 的 `../proto/`**(monorepo)维护。步骤:新建 `../proto/<svc>/<svc>.proto`(`option java_package="com.dating.youjianxin.proto.<svc>"`)+ `pom.xml`,在 `../proto/pom.xml` 的 `<modules>` 注册,加 `../proto/<svc>/python/pyproject.toml`(包名 `dating-proto-youjianxin-<svc>`),跑 `../proto/gen-python.sh` 生成 Python stub,`python -m build` + `twine upload` 到 Nexus pypi-hosted。

### 2. 加依赖

在本仓库 `pyproject.toml` 的 `dependencies` 加 `dating-proto-youjianxin-<svc>==<发布版本>`，`uv lock && uv sync`。

> 发布前本地联调：`uv pip install -e ../proto/<svc>/python` 到 venv。

### 3. 新建 agent 包

```
<name>_agent/
├── __init__.py
├── builder.py     # 用 core/llm + 所需 middleware 构建 agent（参考 vision_agent/builder.py）
├── servicer.py    # 实现 <Svc>Servicer，处理各 RPC（参考 vision_agent/servicer.py）
└── prompts/       # 该 agent 的 prompt 模板
```

- LLM 在 `core/llm.py` 加构造函数（如 `build_<x>_llm()` / 复用 `build_vision_models()`）。
- 需要新的下游客户端就放 `core/clients/`；需要新配置就扩展 `core/config.Settings`。

### 4. 在 server 注册 servicer

在 `server/bootstrap.py` 的 `start_server()` 内：

```python
from dating_proto_youjianxin_<svc> import <svc>_pb2, <svc>_pb2_grpc
from <name>_agent.servicer import <Svc>Servicer

<svc>_pb2_grpc.add_<Svc>Servicer_to_server(<Svc>Servicer(...), server)
# 并把 <svc>_pb2.DESCRIPTOR.services_by_name["<Svc>"].full_name 加入 service_names（reflection）
```

若该 agent 依赖外部（未必就绪的）proto 或 key，可仿照 `_register_vision()` 做 try/except 优雅降级，避免拖垮其它 servicer。

### 5. 打包与配置

- `pyproject.toml` 的 `[tool.hatch.build.targets.wheel] packages` 加上新包名 `"<name>_agent"`。
- `env.example` 补充新 agent 所需的 key / 配置。

### 6. 验证

```bash
uv run python -c "import server.bootstrap, <name>_agent.builder, <name>_agent.servicer; print('ok')"
python -m server   # grpcurl list 应能看到新服务
```

## 开发指南

- **改 ChatAgent 提示词**：`chat_agent/prompts/system.md`（`{{DH_USER_INFO}}` / `{{BH_USER_INFO}}` / `{{INTENT_CONTEXT}}`）。
- **改 Vision 提示词**：`vision_agent/prompts/*.md`。
- **换/调 LLM**：`core/llm.py`（`build_chat_llm` / `build_vision_models`）。
- **LangSmith 追踪**：`export LANGSMITH_TRACING=true LANGSMITH_API_KEY=... LANGSMITH_PROJECT=...`。

## 常见问题

**Q: 如何更换 LLM 后端？**
A: 改 `core/llm.py` 的工厂函数，替换为其它 LangChain 兼容模型。

**Q: 对话历史如何保存？**
A: Chainlit 本地用 `InMemorySaver`（重启丢失）；gRPC 生产用 `AsyncPostgresSaver`（持久化到 PostgreSQL）。Vision 各 RPC 为单轮无状态，不持久化。

**Q: API key 在哪配置？**
A: `DEEPSEEK_API_KEY` / `GROQ_API_KEY` / `GOOGLE_API_KEY` 等环境变量，由 `core/llm.py` 读取。

**Q: 为什么 grpcurl list 只看到 chat.ChatAgent，没有 vision？**
A: 缺 `dating-proto-youjianxin-vision` 依赖或缺 groq/gemini key 时 vision 会被优雅跳过，看启动日志中 `VisionAgent not registered` 的警告。

## 许可

MIT License
