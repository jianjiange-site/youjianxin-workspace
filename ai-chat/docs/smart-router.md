# Smart Router：多模型智能路由

## 背景

AI Chat 当前只使用单一 LLM（DeepSeek v4 Flash），没有容灾能力。当 DeepSeek API 额度耗尽、超时或 5xx 时，对话直接失败。

LangChain 内置了 `ModelFallbackMiddleware` 提供简单的顺序降级，但它的策略是固定的：先试主模型，失败才切。如果需要更灵活的路由策略（轮询、负载均衡、熔断、成本优先），需要基于 `AgentMiddleware.wrap_model_call` 自己实现。

## 内置方案：ModelFallbackMiddleware

LangChain 自带的模型降级 middleware，按参数顺序逐个尝试。

### 用法

```python
from langchain.agents.middleware import ModelFallbackMiddleware

fallback_mw = ModelFallbackMiddleware(
    "deepseek:deepseek-v4-flash",    # 第一个降级
    "openai:gpt-4o-mini",            # 第二个降级
)

agent = create_agent(
    model=llm,  # 主模型（create_agent 的 model 参数）
    middleware=[fallback_mw, ...],
)
```

### 传模型的方式

**字符串**（内部用 `init_chat_model()` 自动初始化，API Key 从标准环境变量读取）：

| 字符串格式 | 环境变量 |
|---|---|
| `"deepseek:deepseek-v4-flash"` | `DEEPSEEK_API_KEY` |
| `"openai:gpt-4o-mini"` | `OPENAI_API_KEY` |
| `"anthropic:claude-sonnet-4-5-20250929"` | `ANTHROPIC_API_KEY` |

**实例**（API Key 由调用方精确控制）：

```python
from langchain_openai import ChatOpenAI

fallback_mw = ModelFallbackMiddleware(
    ChatOpenAI(model="gpt-4o-mini", api_key=SecretStr(os.environ["OPENAI_API_KEY"])),
)
```

### 工作流程

```
handler(request)  # 用主模型调用
  → 成功 → 返回结果
  → 抛异常
    → handler(request.override(model=fallback_1))
      → 成功 → 返回结果
      → 抛异常
        → handler(request.override(model=fallback_2))
          → ...
            → 全部失败 → 抛出最后一个异常
```

### 适用场景

- 模型偶尔抽风（超时、503）
- 额度耗尽自动降级（402/429）
- 不想引入额外依赖的简单场景

### 局限

- 总是先试主模型，不能跳过
- 不支持轮询、负载均衡
- 不知道哪些模型正处于"不可用"状态（每次都傻傻地试一遍）
- 不能按成本、延迟、任务复杂度动态选模型

## 自建方案：SmartRouterMiddleware

当需要更智能的路由策略时，继承 `AgentMiddleware` 覆写 `awrap_model_call` 即可。

### 架构

```
awrap_model_call(request, handler)
  │
  ├─ 1. _pick_model()  ← 根据策略 + 模型健康状态选一个模型
  │
  ├─ 2. handler(request.override(model=chosen))  ← 用选中的模型调用
  │     │
  │     ├─ 成功 → _record_success() → 返回
  │     └─ 失败 → _record_error() → 选下一个重试
  │
  └─ 3. 全部失败 → raise
```

### 实现

```python
# chat_agent/smart_router.py
import time
from collections import defaultdict

from langchain.agents.middleware import AgentMiddleware
from langchain_core.language_models.chat_models import BaseChatModel


class SmartRouterMiddleware(AgentMiddleware):
    """多模型智能路由：轮询 / 首选降级 + 熔断。

    Parameters
    ----------
    models : list[BaseChatModel]
        候选模型列表，顺序影响 prefer_first 策略的优先级。
    strategy : str
        路由策略：
        - "round_robin"  请求轮流分发到各模型
        - "prefer_first" 优先用列表第一个，它不可用时才切
    cooldown_seconds : float
        熔断冷却时间（秒），默认 30。
    error_threshold : int
        连续错误多少次触发熔断，默认 3。
    """

    def __init__(
        self,
        models: list[BaseChatModel],
        strategy: str = "prefer_first",
        cooldown_seconds: float = 30.0,
        error_threshold: int = 3,
    ):
        super().__init__()
        if not models:
            raise ValueError("models must not be empty")
        self.models = models
        self.strategy = strategy
        self.cooldown_seconds = cooldown_seconds
        self.error_threshold = error_threshold
        self._idx = 0
        self._errors: dict[int, int] = defaultdict(int)
        self._cooldown_until: dict[int, float] = {}

    # ── 路由选择 ──────────────────────────────────────────────

    def _pick_model(self) -> BaseChatModel:
        now = time.monotonic()

        if self.strategy == "round_robin":
            return self._pick_round_robin(now)

        # prefer_first（默认）
        return self._pick_prefer_first(now)

    def _pick_round_robin(self, now: float) -> BaseChatModel:
        for _ in range(len(self.models)):
            self._idx = (self._idx + 1) % len(self.models)
            if now >= self._cooldown_until.get(self._idx, 0):
                return self.models[self._idx]
        return self.models[0]  # 全部冷却中，兜底用第一个

    def _pick_prefer_first(self, now: float) -> BaseChatModel:
        for i, m in enumerate(self.models):
            if now >= self._cooldown_until.get(i, 0):
                return m
        return self.models[0]

    # ── 健康追踪 ──────────────────────────────────────────────

    def _record_error(self, model: BaseChatModel) -> None:
        idx = self.models.index(model)
        self._errors[idx] += 1
        if self._errors[idx] >= self.error_threshold:
            self._cooldown_until[idx] = time.monotonic() + self.cooldown_seconds

    def _record_success(self, model: BaseChatModel) -> None:
        idx = self.models.index(model)
        self._errors[idx] = 0  # 成功立即清零

    # ── 核心拦截 ──────────────────────────────────────────────

    async def awrap_model_call(self, request, handler):
        tried: list[BaseChatModel] = []
        last_exc = None

        for _ in range(len(self.models)):
            model = self._pick_model()
            if model in tried:
                continue
            tried.append(model)

            try:
                result = await handler(request.override(model=model))
                self._record_success(model)
                return result
            except Exception:
                import traceback

                traceback.print_exc()
                self._record_error(model)
                last_exc = exc
                continue

        raise last_exc
```

### 接入 agent.py

```python
from chat_agent.smart_router import SmartRouterMiddleware

router_mw = SmartRouterMiddleware(
    models=[
        llm,  # DeepSeek（便宜主力）
        ChatOpenAI(model="gpt-4o-mini", api_key=SecretStr(os.environ["OPENAI_API_KEY"])),
    ],
    strategy="prefer_first",
)

agent = create_agent(
    model=llm,
    middleware=[router_mw, tool_call_limit_mw, user_persona_prompt, summarization_mw],
    ...
)
```

## 策略对比

| 策略 | 行为 | 适用场景 |
|---|---|---|
| `prefer_first` | 始终先调列表第一个模型，它不可用才切。成功一次后熔断清零 | 主力模型便宜但偶尔不稳定，备选兜底 |
| `round_robin` | 请求轮流分发到健康模型，跳过冷却中的 | 多模型均衡负载，避免单模型额度集中消耗 |
| 内置 `ModelFallbackMiddleware` | 从主模型开始，按参数顺序逐个降级，无健康追踪 | 简单场景，不想自己写代码 |

## API Key 管理

每个模型独立管理自己的 API Key：

- **字符串方式**：`"provider:model"` → 内部 `init_chat_model()` 从环境变量读 key
- **实例方式**：直接传 `ChatDeepSeek(api_key=...)` 等实例，key 由调用方管理

用实例方式需要确保 API Key 通过 `SecretStr` 包装（LangChain 安全实践），且从环境变量读取避免硬编码。

## 注意事项

- gRPC server 是多并发场景，`SmartRouterMiddleware` 的状态（计数器、熔断）在当前实现中是**进程内共享**的，多个请求之间会互相影响。对于 `round_robin` 策略这正是预期的；对于熔断，一个请求把模型打入冷却，其他请求也会跳过它。
- 如果项目部署多副本（多进程），每个进程各自维护熔断状态，不跨进程共享。需要全局熔断时可以结合 Redis 等外部存储。
- middleware 顺序：`SmartRouterMiddleware` 应放在 middleware 列表最前面，这样才能在最外层拦截模型调用，不受其他 middleware（如 `SummarizationMiddleware`）内部模型调用的干扰。
