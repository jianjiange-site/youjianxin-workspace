import logging
import os

from langchain_tavily import TavilySearch

logger = logging.getLogger(__name__)

_TAVILY_KEY_ENV = "TAVILY_API_KEY"


def build_web_search() -> TavilySearch | None:
    """构造 Tavily 联网搜索工具；TAVILY_API_KEY 缺失时返回 None（仅告警，不 crash）。

    TavilySearch（langchain-tavily 官方集成，langchain_community 旧工具已弃用）在**实例化时**
    即校验 TAVILY_API_KEY，故必须放在 build 函数里、在 dotenv 加载之后调用——与 core/llm.py 的
    build_chat_llm() / build_vision_models() 同一模式，避免在 import 期就硬失败拖垮整个 app。

    include_answer / include_raw_content 在实例化时固定（Tavily 不允许 invoke 时覆盖，以免响应体
    大小不可控撑爆上下文）；topic / search_depth / time_range 等仍可在 invoke 时覆盖。
    实时聊天对延迟敏感：basic 深度 + 少量结果 + 让 Tavily 直接合成 answer，控制延迟与 token。
    """
    if not os.environ.get(_TAVILY_KEY_ENV):
        logger.warning("TAVILY_API_KEY 未配置，web_search 工具不可用，已跳过注册")
        return None
    return TavilySearch(
        max_results=4,
        topic="general",            # 模型可在 invoke 时临时传 news / finance 覆盖
        search_depth="basic",
        include_answer="advanced",  # Tavily 预合成答案，模型常可直接转述
        include_raw_content=False,  # 只要摘要，避免大段正文撑爆 token
    )
