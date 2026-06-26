# Smart Router：兜底（Fallback）模型层

> 状态：**已实施**（fallback 机制就位，`build_vision_fallback_models()` 暂返回 `[]`，
> DeepSeek 多模态模型待后续接入）。
> 关联：[smart-router.md](./smart-router.md)（SmartRouterMiddleware 现状）

## 背景

`vision_agent` 当前用 `SmartRouterMiddleware([groq, gemini], strategy="round_robin")`
在两个多模态模型间轮询（`vision_agent/builder.py` `_build_vision_agent`）。

需求：后续把 DeepSeek 作为**兜底模型**——只有当 groq 和 gemini **都**不可用时才切到
DeepSeek，平时不参与轮询。

### 为什么不能直接追加进 models 列表

`round_robin` 把流量**均匀**分到列表里每个模型。如果直接写成
`[groq, gemini, deepseek]`，DeepSeek 会吃掉约 1/3 的正常请求，这不是「兜底」语义。

`prefer_first` 也不合适：它会把 gemini 当成 groq 的备选、DeepSeek 当成第三选，但表达不出
「groq、gemini 同级轮询，DeepSeek 独立殿后」这种**分层**关系。

因此需要给 router 增加一个独立于轮询池的 **fallback 层**。

### 触发条件（已确认）

groq、gemini **都**失败 / **都**处于熔断冷却期时，才尝试 fallback。具体两种路径：

1. **同请求内级联失败**：一次请求里 groq 抛错 → gemini 抛错 → 才轮到 DeepSeek。
2. **熔断后直达**：主池模型连续错满 `error_threshold` 进入冷却期，期间请求跳过主池、直达
   fallback，不必每次都先撞一遍错误。

## 方案

### 1. `core/middlewares/smart_router.py` — 增加 fallback 层

`__init__` 新增参数 `fallback_models: list[BaseChatModel] | None = None`：

- `self.models` 保持为**主池**（参与 `round_robin` / `prefer_first` 策略 + 熔断）。
- 新增 `self.fallback_models = fallback_models or []`。
- 熔断/错误计数注册表覆盖两层：
  `self._model_index = {id(m): i for i, m in enumerate(self.models + self.fallback_models)}`，
  主池占 `0..n-1`，fallback 占 `n..`。`_errors` / `_cooldown_until` 仍按该组合 index 计。
- `self._idx`（round_robin 游标）仍只在主池长度内循环。

把当前 `_pick_*` + 两份重复的 `awrap_model_call` / `wrap_model_call` 重构为一个统一的
「本次请求候选顺序」生成器，让 fallback 成为主池耗尽后的尾部追加：

```python
def _ordered_candidates(self, now: float) -> list[BaseChatModel]:
    # 主池：按策略排序，跳过冷却中的
    if self.strategy == "round_robin":
        self._idx = (self._idx + 1) % len(self.models)          # 每请求推进一次，保持分流
        rotated = self.models[self._idx:] + self.models[:self._idx]
    else:  # prefer_first
        rotated = list(self.models)
    primary = [m for m in rotated
               if now >= self._cooldown_until.get(self._model_index[id(m)], 0)]
    # fallback：仅作尾部追加，跳过冷却中的
    fallback = [m for m in self.fallback_models
                if now >= self._cooldown_until.get(self._model_index[id(m)], 0)]
    candidates = primary + fallback
    return candidates or list(self.models)   # 全冷却时兜底全试一遍，避免 raise None
```

`awrap_model_call` / `wrap_model_call` 改为遍历 `_ordered_candidates(now)`：逐个
`handler(request.override(model=model))`，成功 `_record_success` 返回，异常
`_record_error` + 记 `last_exc` 继续；全失败再 `raise last_exc`。两个方法逻辑对称
（一异步一同步）。

- `_record_error` / `_record_success` 已按 `self._model_index[id(model)]` 取值，注册表含
  fallback 后无需改动。
- 顺带修掉现存 bug：原代码全冷却时 `last_exc` 为 `None` 会 `raise None`；
  `candidates or list(self.models)` 保证候选非空。
- 更新类 docstring，补充 `fallback_models` 参数说明。

### 2. `core/llm.py` — 新增 fallback 模型工厂

新增 `build_vision_fallback_models() -> list[BaseChatModel]`，**先返回 `[]`**（机制就位、
不改变现有行为），docstring 写明后续接 DeepSeek 多模态的方式（读 `DEEPSEEK_VISION_MODEL`
env、用 `ChatDeepSeek` 构造），作为接线锚点。

> 注意：DeepSeek 当前是聊天模型 `deepseek-v4-flash`，**非多模态**。实际接线需等
> DeepSeek 提供可用的多模态模型，再在此函数填入。

### 3. `vision_agent/builder.py` — 接入 fallback

```python
from core.llm import build_vision_models, build_vision_fallback_models
...
models = build_vision_models()
router = SmartRouterMiddleware(
    models, strategy="round_robin", fallback_models=build_vision_fallback_models()
)
```

`create_agent(model=models[0], ...)` 不变（默认模型仍取主池首个，实际每次调用由 router 覆盖）。

## 策略对比（补充 fallback 维度）

| 维度 | 主池（round_robin） | fallback 层 |
|---|---|---|
| 正常流量 | 轮流分发 | **不参与** |
| 触发条件 | 始终参与 | 主池全部失败 / 全部冷却 |
| 熔断 | 各自独立计数 + 冷却 | 同样有独立熔断 |

## 验证

仓库无测试套件，用一次性脚本验证 router 排序与熔断切换：

```bash
uv run python - <<'PY'
# 构造假模型（成功/抛错）+ fallback，断言：
#  1) round_robin 在主池两个间轮询；
#  2) 两个主模型同请求内都抛错时切到 fallback；
#  3) 主模型连续错 error_threshold 次进入冷却后，请求直达 fallback；
#  4) 全部冷却时不抛 None。
PY
```

并 `uv run python -c "import vision_agent.builder, core.llm, core.middlewares"` 确认导入无误。
