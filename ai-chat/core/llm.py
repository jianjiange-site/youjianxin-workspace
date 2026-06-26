"""LLM 工厂：集中构造各 agent 使用的 chat model。

- chat：DeepSeek（deepseek-v4-flash）
- vision：Groq + Gemini + 智谱 Z.ai（GLM-4V-Flash）多模态，作为 SmartRouterMiddleware 的
  候选列表，按 round_robin 轮询路由，出错的模型熔断后自动跳过。Z.ai 走 OpenAI 兼容接口
  （ChatOpenAI + 自定义 base_url）。

provider key 走各自 SDK 默认环境变量：DEEPSEEK_API_KEY / GROQ_API_KEY / GOOGLE_API_KEY /
ZAI_API_KEY。
"""

import os

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_deepseek import ChatDeepSeek
from pydantic import SecretStr


def build_chat_llm() -> ChatDeepSeek:
    """DH 聊天用 LLM：DeepSeek，thinking 关闭，temperature 0.7。"""
    return ChatDeepSeek(
        model="deepseek-v4-flash",
        api_key=SecretStr(os.environ["DEEPSEEK_API_KEY"]),
        temperature=0.7,
        extra_body={"thinking": {"type": "disabled"}},
    )


def build_vision_models() -> list[BaseChatModel]:
    """vision 多模态候选模型（懒加载 provider 依赖）。

    交给 SmartRouterMiddleware 按 round_robin 轮询调度。模型 id 走 env 可覆盖，
    默认值上线前需确认 groq / gemini / zai 当前可用的多模态模型。Z.ai 走 OpenAI 兼容
    接口（ChatOpenAI + ZAI_BASE_URL）。
    """
    from langchain_groq import ChatGroq
    from langchain_google_genai import ChatGoogleGenerativeAI
    from langchain_openai import ChatOpenAI

    groq = ChatGroq(
        model=os.environ.get("GROQ_VISION_MODEL", "meta-llama/llama-4-scout-17b-16e-instruct"),
        temperature=0,
    )
    gemini = ChatGoogleGenerativeAI(
        model=os.environ.get("GEMINI_VISION_MODEL", "gemini-3.1-flash-lite"),
        temperature=0,
    )
    zai = ChatOpenAI(
        model=os.environ.get("ZAI_VISION_MODEL", "glm-4v-flash"),
        api_key=SecretStr(os.environ["ZAI_API_KEY"]),
        base_url=os.environ.get("ZAI_BASE_URL", "https://api.z.ai/api/paas/v4/"),
        temperature=0,
    )
    return [groq, gemini, zai]


def build_vision_fallback_models() -> list[BaseChatModel]:
    """vision 兜底模型层：仅当主池（groq / gemini）全部失败或熔断时才启用。

    交给 SmartRouterMiddleware 的 fallback_models，不参与正常轮询。

    当前返回空列表（兜底机制就位、不改变现有行为）。后续 DeepSeek 提供可用的多模态
    模型后，在此接入即可，例如：

        from langchain_deepseek import ChatDeepSeek
        from pydantic import SecretStr

        deepseek = ChatDeepSeek(
            model=os.environ.get("DEEPSEEK_VISION_MODEL", "deepseek-vision-..."),
            api_key=SecretStr(os.environ["DEEPSEEK_API_KEY"]),
            temperature=0,
        )
        return [deepseek]

    注意：DeepSeek 当前的 deepseek-v4-flash 是聊天模型，非多模态，不能直接用于 vision。
    """
    return []
