# 项目架构重组 + vision_agent（图像理解 / 颜值打分 / 头像分析，gRPC 接入）

## Context（为什么做这件事）

当前仓库结构散乱：chat 逻辑被拆在两处——`agent.py`、`prompts/` 在**仓库根**，而 `chat_agent/grpc_server.py`、`intent_classifier.py`、`clients/` 在 **`chat_agent/` 包内**；共享基础设施（`models.py`、`middlewares/smart_router.py`、`nacos_client/`）散落在根。`SmartRouterMiddleware`（round_robin + prefer_first + 熔断）已实现但**未接入任何 agent**。

本次目标：
1. 把项目重组为「**扁平特性包 + `core/` + `server/`**」，让一个 gRPC 进程承载多个 servicer。现有 ChatAgent 迁入新结构，**行为不变**。
2. 新增 **vision_agent**，提供三个能力并真正通过 **gRPC** 对外：
   - **图像理解**（Understand）——按 prompt 对图片做自然语言理解（prompt 自定义）。
   - **颜值打分**（ScoreFace）——返回结构化分数。
   - **头像/资料照分析**（AnalyzeProfilePhoto）——真人脸→「描述 | 风格标签」字符串（含年龄段），非人→`not_human`，虚构角色→`anime_human`，输出 `{"analysis": "..."}`。
   三者均经 `SmartRouterMiddleware([groq, gemini])` 多模态路由，groq 失败回退 gemini。
3. vision 的 gRPC 契约在 proto 仓库 `/Users/zhou/code/proto` 新增并经 Jenkins 发布到 Nexus（`jianjiange-proto-vision`），chat-agent 再加依赖。

**决策（已确认）：**
- 单进程多 servicer：一个 gRPC server / 一个端口，`ChatAgentServicer` + `VisionAgentServicer` 同进程，共享单个 Nacos 注册。
- 布局：`chat_agent/` + `vision_agent/` + `core/` + `server/`。
- 颜值打分：**去掉种族部分实现**——保留 `status` / `appearance` / `sexual_attractiveness_score` 三维、非人脸→全 0、虚构角色→全 50、多图取平均、仅返回 JSON；**移除** `race` 字段、原规则 5（按种族压低分数）、原规则 6（判定种族）。原因：按种族对真实用户差异化打分构成歧视且涉敏感个人数据合规风险，不予实现。

---

## 目标目录结构

```
chat_agent/
  __init__.py
  builder.py            ← /agent.py（build_agent / UserContext / user_persona_prompt）
  intent_classifier.py  （改 import + prompt 路径）
  servicer.py           ← 从 grpc_server.py 抽出 ChatAgentServicer
  prompts/              ← /prompts/ 整体迁入
    __init__.py  system.md  dating_summary_prompt.md  intent_classification.md  utils.py
vision_agent/                       ← 新增
  __init__.py
  builder.py            _build_vision_agent(system_md) → understand / score / profile 三个 agent（各含 SmartRouter[groq,gemini]）
  servicer.py           VisionAgentServicer：Understand / ScoreFace / AnalyzeProfilePhoto 三个 RPC
  prompts/
    __init__.py  understand_system.md  face_score_system.md  profile_photo_system.md
core/                               ← 共享基础设施
  __init__.py
  models.py             ← /models.py
  config.py             新增：集中读取 env
  llm.py                新增：build_chat_llm() / build_vision_models()
  middlewares/          ← /middlewares/
    __init__.py  smart_router.py
  clients/              ← /chat_agent/clients/
    __init__.py  user_client.py
  nacos_client/         ← /nacos_client/
    __init__.py  client.py  resolver.py
  tools/                ← /tools/（weather mock，未用；亦可删除）
    __init__.py  weather.py
server/                             ← 新增：gRPC 启动层
  __init__.py
  bootstrap.py          start_server()：PG pool / 信号 / 注册 Chat+Vision 两 servicer / 反射
  __main__.py           读 env、init nacos、build llm、asyncio.run
main.py                 Chainlit 入口（改 import）
```

启动命令：`python -m chat_agent.grpc_server` → **`python -m server`**。

---

## Part A — 重组（chat 行为不变）

### 迁移映射（用 `git mv` 保留历史）

| 现位置 | 目标位置 |
|---|---|
| `agent.py` | `chat_agent/builder.py` |
| `models.py` | `core/models.py` |
| `prompts/`（`utils.py` + 3 个 `.md`） | `chat_agent/prompts/` |
| `middlewares/` | `core/middlewares/` |
| `nacos_client/` | `core/nacos_client/` |
| `chat_agent/clients/` | `core/clients/` |
| `tools/` | `core/tools/`（或删除） |
| `chat_agent/grpc_server.py` | **拆分** → `chat_agent/servicer.py` + `server/bootstrap.py` + `server/__main__.py` |

### import 改写规则（逐处替换）

- `from models import UserInfo` → `from core.models import UserInfo`
- `from prompts.utils import ...` → `from chat_agent.prompts.utils import ...`
- `from nacos_client...` → `from core.nacos_client...`
- `from middlewares.smart_router import ...` → `from core.middlewares.smart_router import ...`
- `from chat_agent.clients import UserServiceClient` → `from core.clients import UserServiceClient`
- `from agent import build_agent, UserContext, llm` → `from chat_agent.builder import build_agent, UserContext` + `from core.llm import build_chat_llm`

### 两处文件路径必改

- `chat_agent/intent_classifier.py`：`Path(__file__).parent.parent / "prompts" / ...` → **`Path(__file__).parent / "prompts" / ...`**
- `chat_agent/prompts/utils.py` 的 `_PROMPTS_DIR = Path(__file__).parent` **不变**。

### 拆分细节

- `chat_agent/builder.py`：删模块级 `llm`（移至 `core/llm.build_chat_llm()`），保留 `UserContext` / `user_persona_prompt` / `build_agent(llm, checkpointer)`。
- `chat_agent/servicer.py`：原样搬入 `ChatAgentServicer`。
- `server/bootstrap.py`：保留 `start_server` 生命周期（新增 vision 注册）。
- `server/__main__.py`：原 `__main__` 块，用 `core.config.Settings.from_env()` 读 env。

---

## Part B — proto 仓库新增 vision（前置；`/Users/zhou/code/proto`）

> README 硬性规定：业务工程禁止跑 protoc / 拷贝 proto，一律从 Nexus 拉包。按「新增服务」6 步（参照 `match` 范例）：

1. `proto/vision/VERSION` → `0.1.0`
2. `proto/vision/vision.proto`：
   ```protobuf
   syntax = "proto3";
   package vision;
   option java_package = "com.jianjiange.proto.vision";
   option java_multiple_files = true;
   option java_outer_classname = "VisionProto";

   service VisionAgent {
     rpc Understand(UnderstandRequest) returns (UnderstandResponse);
     rpc ScoreFace(ScoreFaceRequest)  returns (ScoreFaceResponse);
     rpc AnalyzeProfilePhoto(AnalyzeProfilePhotoRequest) returns (AnalyzeProfilePhotoResponse);
   }
   message UnderstandRequest {
     repeated string image_urls = 1;
     string prompt = 2;
   }
   message UnderstandResponse { string content = 1; }
   message ScoreFaceRequest  { repeated string image_urls = 1; }
   message ScoreFaceResponse {
     int32 status = 1;
     int32 appearance = 2;
     int32 sexual_attractiveness_score = 3;
   }
   message AnalyzeProfilePhotoRequest  { string image_url = 1; }
   message AnalyzeProfilePhotoResponse { string analysis = 1; }
   message ErrorDetail { int32 code = 1; string message = 2; }
   ```
3. `java/vision-proto/pom.xml`（复制 user-proto，改 artifactId / protoSourceRoot）。
4. `python/vision-proto/pyproject.toml`（复制 chat-proto，`name = "jianjiange-proto-vision"`）+ `src/jianjiange_proto_vision/__init__.py`。
5. `ts/vision-proto/`（复制 user-proto，`@jianjiange/vision-proto`）。
6. `Jenkinsfile`：`ALL_SERVICES` 加 `'vision'`；加 `booleanParam SVC_vision`。
7. 推 `dev` → Jenkins `proto-snapshot` 勾 `SVC_vision` → `jianjiange-proto-vision==0.1.0.dev<N>`。

> **本地联调桥接**：在 proto 仓库 `python/vision-proto` 内本地生成 stub 后 `uv pip install -e /Users/zhou/code/proto/python/vision-proto` 进 chat-agent venv；发布后切回 Nexus 依赖。chat-agent 内不跑 protoc、不拷 proto。

---

## Part C — vision_agent 实现（chat-agent）

### `core/llm.py`
- `build_chat_llm() -> ChatDeepSeek`（从 agent.py 平移）。
- `build_vision_models() -> list[BaseChatModel]`：`[ChatGroq(model=GROQ_VISION_MODEL), ChatGoogleGenerativeAI(model=GEMINI_VISION_MODEL)]`，模型名走 env。

### `vision_agent/builder.py`
- `_build_vision_agent(system_md: str)` 通用工厂：`models=build_vision_models()`；`router=SmartRouterMiddleware(models, strategy="round_robin")`；`create_agent(model=models[0], tools=[], middleware=[router], checkpointer=None, 静态 system=system_md)`。据此构建 understand / score / profile 三个。
- `_image_message(image_urls, prompt)`：构造多模态 `HumanMessage(content=[{"type":"text",...}, {"type":"image_url",...}])`。

### `vision_agent/prompts/face_score_system.md`（去种族版）
```
You are a recommendation expert for a social dating app. You accurately evaluate users
across multiple dimensions from their photos so we can match users well.

TASK: For the image(s) provided, return a SINGLE JSON object with exactly these fields:
- "status": 0–100  overall status/condition (0 = very bad, 100 = top status)
- "appearance": 0–100  physical attractiveness (0 = very unattractive, 100 = perfect)
- "sexual_attractiveness_score": 0–100  sexual appeal (0 = none, 100 = extremely high)

RULES:
1. Return a single JSON object with exactly the structure above. No extra fields.
2. If the image has NO HUMAN FACE / is NOT HUMAN (animal, object, landscape, cartoon, anime):
   {"status": 0, "appearance": 0, "sexual_attractiveness_score": 0}
3. If it contains fictional characters (anime, cartoon, movie/TV):
   {"status": 50, "appearance": 50, "sexual_attractiveness_score": 50}
4. For multiple images, average each score across images; output one JSON object.
5. DO NOT include any explanations or additional text.
```

### `vision_agent/prompts/profile_photo_system.md`（头像/资料照分析）
```
You are a profile-photo analysis system for a dating app. You receive ONE user profile photo.

DECISION:
1) Real human face present → analysis string in the form:
   "[one 15–25 word description, including apparent age range and visible style/setting] | [tag1], [tag2], [tag3]"
   - 1–3 comma-separated style tags after " | " describing clothing aesthetics / grooming / overall vibe
     (e.g. professional, streetwear, minimalist, glam, bohemian, athleisure, hipster, casual). Omit if N/A; max 3.
2) Non-human / no face (animal, object, landscape) → analysis = "not_human".
3) Fictional character (anime, cartoon, CGI) → analysis = "anime_human".

OUTPUT: a single JSON object, nothing else:
{"analysis": "<analysis string | not_human | anime_human>"}

CONSTRAINTS:
- Never describe non-human images.
- The analysis value is a plain string: no extra brackets, quotes or braces inside it.
- No text beyond the JSON object.

Examples:
{"analysis": "Asian male in late 20s wearing vintage leather jacket at music festival | hipster, casual, musician"}
{"analysis": "not_human"}
{"analysis": "anime_human"}
```

（`understand_system.md` 自定义：扮演图像理解助手，按用户 prompt 描述/分析图片，无 prompt 时默认中文描述要点。）

### `vision_agent/servicer.py`
- `VisionAgentServicer(vision_pb2_grpc.VisionAgentServicer)`，`__init__` 构建三个 agent。
- `Understand`：调 understand agent → `UnderstandResponse(content=...)`。
- `ScoreFace`：调 score agent → `_extract_json` → clamp 0–100，失败回退 `{0,0,0}` → `ScoreFaceResponse(...)`。
- `AnalyzeProfilePhoto`：调 profile agent → `_extract_json` 取 `analysis`，失败回退原文 → `AnalyzeProfilePhotoResponse(analysis=...)`。
- 入参为空 → `INVALID_ARGUMENT`。

---

## Part D — server 单进程多 servicer 注册

`server/bootstrap.py` 的 `start_server` 内同时注册 chat + vision；反射 service_names 含两者；单个 Nacos 注册不变。

---

## 依赖 / env / 部署

- `pyproject.toml`：加 `langchain-groq`、`langchain-google-genai`；发布后加 `jianjiange-proto-vision`。加 `[build-system]`（hatchling）+ `[tool.hatch.build.targets.wheel] packages = ["chat_agent","vision_agent","core","server"]`。
- `env.example` / `.env`：加 `GROQ_API_KEY`、`GOOGLE_API_KEY`，可选 `GROQ_VISION_MODEL`、`GEMINI_VISION_MODEL`。
- `Dockerfile`：CMD → `["python","-m","server"]`。
- `docker-compose.yml` / `Jenkinsfile`：不变。
- `CLAUDE.md`：更新结构、职责、启动命令、vision 段落。

---

## 实施顺序

1. Part A 重组 + 不依赖 vision proto 的部分（core/llm、core/config、vision_agent/builder、prompts）——chat 先跑通。
2. Part B：proto 仓库加 vision、发布（手动 Jenkins）。发布前用本地桥接。
3. 接线：加依赖，补 servicer 与 bootstrap vision 注册。

---

## 验证

1. `uv sync`。
2. import 冒烟（全包导入）。
3. `chainlit run main.py` chat 不回归。
4. `python -m server` + `grpcurl -plaintext localhost:50051 list` 列出 `chat.ChatAgent` 与 `vision.VisionAgent`。
5. `Understand` / `ScoreFace` / `AnalyzeProfilePhoto` grpcurl 调用；断开 groq 验证 gemini 回退。
6. `docker compose build ai-chat` 通过。
