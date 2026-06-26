# 联网搜索工具：web_search（Tavily）+ read_url（Jina Reader）

> 状态：**已实施**。两个工具已接入 `chat_agent/builder.py` 的 `tools=[...]`。
> 关联：[dynamic-prompt.md](./dynamic-prompt.md)（agent 装配）、`core/tools/weather.py`（工具风格基准）

## 背景

DH（AI 人设）此前对真实世界没有感知，只能依赖 system prompt 的静态人设与对话历史。
为让 DH 能感知真实世界（最新新闻、当下热点、不确定的事实），新增联网能力：

- **`web_search`** —— 用 Tavily 做搜索发现，返回合成答案 + 来源摘要（默认、快路径）。
- **`read_url`** —— 用 Jina Reader 把某个网页抽成正文（按需深读）。

### 为什么是两个可组合工具，而非一个组合工具

实时聊天对延迟敏感。`web_search` 单次往返 ~1–2s 即给出 answer + 来源，覆盖绝大多数场景；
Jina Reader 较慢，独立成 `read_url`，让 DH 仅在确需某页全文时按需深读，避免每次搜索都付 Jina 延迟。
DH 流程：先 `web_search` → （可选）挑一个 URL 用 `read_url` 深读。

## 方案

### 1. `core/tools/web_search.py` —— Tavily 官方集成 + 工厂

搜索用 **`langchain-tavily`**（Tavily 官方维护、LangChain 官方推荐；`langchain_community` 旧 Tavily
工具已弃用），符合 CLAUDE.md「以 docs.langchain.com 官网为准」。weather.py 手写是因为 NWS 无官方工具，
Tavily 有官方工具就用官方的。

关键坑：**`TavilySearch` 在实例化时即校验 `TAVILY_API_KEY`**（不是 invoke 时）。若在模块顶层
`web_search = TavilySearch(...)`，一旦 key 缺失，`import core.tools` 会硬崩，拖垮整个 app
（server / 本地 Chainlit 调试）。

故采用**工厂函数** `build_web_search() -> TavilySearch | None`，在 dotenv 加载之后调用——与
`core/llm.py` 的 `build_chat_llm()` / `build_vision_models()` 同一模式。key 缺失时返回 `None`
并 `logger.warning`，agent 仍能正常构建，只是不注册该工具。

```python
def build_web_search() -> TavilySearch | None:
    if not os.environ.get("TAVILY_API_KEY"):
        logger.warning("TAVILY_API_KEY 未配置，web_search 工具不可用，已跳过注册")
        return None
    return TavilySearch(
        max_results=4,
        topic="general",            # invoke 时模型可临时传 news / finance 覆盖
        search_depth="basic",
        include_answer="advanced",  # Tavily 预合成答案，模型常可直接转述
        include_raw_content=False,  # 只要摘要，避免大段正文撑爆 token
    )
```

> `include_answer` / `include_raw_content` 在实例化时固定（Tavily 不允许 invoke 时覆盖，
> 以免响应体大小不可控）；`topic` / `search_depth` / `time_range` 等仍可在 invoke 时覆盖。
> 工具名为集成自带的 `tavily_search`。

### 2. `core/tools/web_reader.py` —— Jina Reader 深读（手写）

Jina Reader（`r.jina.ai`）没有对应的官方 LangChain 工具，故沿用 `weather.py` 风格手写：
`@tool` + async + `httpx.AsyncClient` + Pydantic 输入 schema + **容错降级**（异常返回友好文案，
不抛给 LLM 当 tool error）。

- `GET https://r.jina.ai/<完整url>`，headers：`X-Return-Format: text`（最紧凑）、
  `X-Engine: direct`（轻量、不渲染 JS、优先速度）、`X-Timeout: 12`。
- **无需 API key**：无 key 也能用（按 IP 限流）；存在 `JINA_API_KEY` 时才加 `Authorization` 头，
  仅为提高限流额度。
- 正文截断到 `_READ_MAX_CHARS = 4000`，避免单页全文撑爆上下文。

### 3. 接线

- `core/tools/__init__.py` 导出 `get_weather` / `build_web_search` / `read_url`。
- `chat_agent/builder.py` 在 `build_agent()` 内组装 tools：

```python
tools = [get_weather, read_url]
web_search = build_web_search()   # 无 TAVILY_API_KEY 时返回 None
if web_search is not None:
    tools.append(web_search)
```

已有 `ToolCallLimitMiddleware(run_limit=10)` 天然限制工具被刷，无需额外限流。

## 配置（env.example）

```
# 联网搜索：langchain-tavily 搜索 + Jina Reader（r.jina.ai）深读
TAVILY_API_KEY=your_tavily_api_key
# 可选：Jina Reader 不填 key 也能用（按 IP 限流），填 key 仅为提高限流额度
# JINA_API_KEY=your_jina_api_key
```

依赖：`pyproject.toml` 新增 `langchain-tavily>=0.2.0`（经现有 Nexus group 索引安装，传递引入
`tavily-python`）。`web_reader` 仅用已在的 `httpx`。

## 设计决策小结

| 决策 | 选择 | 理由 |
|---|---|---|
| 搜索实现 | `langchain-tavily` 官方集成 | 项目约定「以 LangChain 官网为准」；旧 community 工具已弃用 |
| 深读实现 | Jina Reader 手写 httpx | 无官方 LangChain 集成；沿用 weather.py 容错风格 |
| 工具粒度 | 两个可组合工具 | 延迟敏感；Jina 慢，仅按需深读 |
| key 处理 | 工厂 + 返回 None | TavilySearch 实例化即校验 key，避免 import 期硬崩 |
| Jina key | 可选 | r.jina.ai 无 key 可用，配 key 仅提额 |

## 验证

```bash
uv add langchain-tavily       # 已执行，安装 langchain-tavily==0.2.18

# 1) 无 key：import 不崩，build_web_search() 返回 None + 告警
uv run python -c "from core.tools import build_web_search; print(build_web_search())"

# 2) 有 key：构造出 TavilySearch（name=tavily_search）
TAVILY_API_KEY=dummy uv run python -c "from core.tools import build_web_search; print(build_web_search().name)"

# 3) read_url 实测（无 key、走 Jina）
uv run python -c "import asyncio; from core.tools import read_url; \
print(asyncio.run(read_url.ainvoke({'url':'https://example.com'})))"

# 4) read_url 故障降级（非法 URL 返回友好文案、不抛异常）
uv run python -c "import asyncio; from core.tools import read_url; \
print(asyncio.run(read_url.ainvoke({'url':'https://x.invalid'})))"
```

已验证：无 key 降级、有 key 构造、Jina 实读、非法 URL 降级、改动文件 `py_compile` 均通过。
端到端（需真实 `TAVILY_API_KEY`）：`chainlit run main.py`，对 DH 说一句需现实信息的话
（如「最近有什么大新闻」），观察是否触发 `web_search`、必要时 `read_url`，回复自然不暴露工具痕迹。
