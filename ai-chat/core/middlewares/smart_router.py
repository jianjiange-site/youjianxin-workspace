import logging
import time
from collections import defaultdict

from langchain.agents.middleware import AgentMiddleware
from langchain_core.language_models.chat_models import BaseChatModel

logger = logging.getLogger(__name__)


class SmartRouterMiddleware(AgentMiddleware):
    """多模型智能路由：轮询 / 首选降级 + 熔断 + 兜底层。

    Parameters
    ----------
    models : list[BaseChatModel]
        主池候选模型列表，顺序影响 prefer_first 策略的优先级。正常流量按 strategy
        在主池内分发。
    strategy : str
        路由策略：
        - "round_robin"  请求轮流分发到各模型
        - "prefer_first" 优先用列表第一个，它不可用时才切
    cooldown_seconds : float
        熔断冷却时间（秒），默认 30。
    error_threshold : int
        连续错误多少次触发熔断，默认 3。
    fallback_models : list[BaseChatModel] | None
        兜底模型层，**不参与**正常分发。仅当主池模型在本次请求内全部失败、或全部处于
        熔断冷却期时，才作为候选尾部追加。兜底模型自身也有独立熔断。
    """

    def __init__(
        self,
        models: list[BaseChatModel],
        strategy: str = "prefer_first",
        cooldown_seconds: float = 30.0,
        error_threshold: int = 3,
        fallback_models: list[BaseChatModel] | None = None,
    ):
        super().__init__()
        if not models:
            raise ValueError("models must not be empty")
        self.models = models
        self.fallback_models = fallback_models or []
        self.strategy = strategy
        self.cooldown_seconds = cooldown_seconds
        self.error_threshold = error_threshold
        self._idx = len(self.models) - 1
        self._errors: dict[int, int] = defaultdict(int)
        self._cooldown_until: dict[int, float] = {}
        # 熔断/错误计数注册表覆盖主池 + 兜底两层：主池占 0..n-1，兜底占 n..
        self._model_index: dict[int, int] = {
            id(m): i for i, m in enumerate(self.models + self.fallback_models)
        }

    # ── 路由选择 ──────────────────────────────────────────────

    def _available(self, model: BaseChatModel, now: float) -> bool:
        """模型未处于熔断冷却期。"""
        return now >= self._cooldown_until.get(self._model_index[id(model)], 0)

    def _ordered_candidates(self, now: float) -> list[BaseChatModel]:
        """本次请求的模型尝试顺序：主池（按策略，跳过冷却中的）+ 兜底层尾部追加。

        兜底模型仅在主池候选耗尽后才被尝试，从而实现「主池全部不可用才兜底」语义。
        全部冷却时返回完整主池，避免候选为空导致 raise None。
        """
        if self.strategy == "round_robin":
            self._idx = (self._idx + 1) % len(self.models)  # 每请求推进一次，保持分流
            rotated = self.models[self._idx :] + self.models[: self._idx]
        else:  # prefer_first
            rotated = list(self.models)

        primary = [m for m in rotated if self._available(m, now)]
        fallback = [m for m in self.fallback_models if self._available(m, now)]
        return primary + fallback or list(self.models)

    # ── 健康追踪 ──────────────────────────────────────────────

    def _record_error(self, model: BaseChatModel) -> None:
        idx = self._model_index[id(model)]
        self._errors[idx] += 1
        if self._errors[idx] >= self.error_threshold:
            self._cooldown_until[idx] = time.monotonic() + self.cooldown_seconds

    def _record_success(self, model: BaseChatModel) -> None:
        idx = self._model_index[id(model)]
        self._errors[idx] = 0

    # ── 核心拦截 ──────────────────────────────────────────────

    async def awrap_model_call(self, request, handler):
        last_exc = None
        now = time.monotonic()

        for model in self._ordered_candidates(now):
            try:
                result = await handler(request.override(model=model))
                self._record_success(model)
                return result
            except Exception as exc:
                logger.warning(
                    "Model %s failed: %s", getattr(model, "model", model), exc,
                    exc_info=True,
                )
                self._record_error(model)
                last_exc = exc
                continue

        raise last_exc

    def wrap_model_call(self, request, handler):
        last_exc = None
        now = time.monotonic()

        for model in self._ordered_candidates(now):
            try:
                result = handler(request.override(model=model))
                self._record_success(model)
                return result
            except Exception as exc:
                logger.warning(
                    "Model %s failed: %s", getattr(model, "model", model), exc,
                    exc_info=True,
                )
                self._record_error(model)
                last_exc = exc
                continue

        raise last_exc
