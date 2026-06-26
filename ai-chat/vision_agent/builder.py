"""vision agent 工厂：图像理解 / 颜值打分 / 头像分析。

三者均为无状态单轮 agent，通过 SmartRouterMiddleware 在 groq / gemini 多模态模型间
轮询路由 + 熔断回退（round_robin：请求轮流分发，出错的模型熔断后自动跳过）。
"""

import asyncio
import base64
from pathlib import Path
from urllib.parse import urlparse

import httpx
from langchain.agents import create_agent
from langchain_core.messages import HumanMessage

from core.llm import build_vision_fallback_models, build_vision_models
from core.middlewares import SmartRouterMiddleware
from vision_agent.schemas import FaceScore, ProfileAnalysis

_PROMPTS_DIR = Path(__file__).parent / "prompts"

_EXT_TO_MIME = {
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
    "png": "image/png",
    "webp": "image/webp",
    "gif": "image/gif",
}


def _load_prompt(name: str) -> str:
    return (_PROMPTS_DIR / name).read_text(encoding="utf-8")


def _build_vision_agent(system_md: str, response_format=None):
    """构建一个无状态单轮 vision agent：静态 system prompt + SmartRouter 路由。

    response_format 非空时启用结构化输出（Pydantic schema），结果在
    result["structured_response"]，解析在 model 节点内完成，仍经过 SmartRouter 路由。
    """
    models = build_vision_models()
    router = SmartRouterMiddleware(
        models,
        strategy="round_robin",
        fallback_models=build_vision_fallback_models(),
    )
    return create_agent(
        model=models[0],
        tools=[],
        middleware=[router],
        system_prompt=system_md,
        response_format=response_format,
        checkpointer=None,
    )


def build_understand_agent():
    return _build_vision_agent(_load_prompt("understand_system.md"))


def build_score_agent():
    return _build_vision_agent(_load_prompt("face_score_system.md"), FaceScore)


def build_profile_agent():
    return _build_vision_agent(_load_prompt("profile_photo_system.md"), ProfileAnalysis)


async def _fetch_as_data_url(client: httpx.AsyncClient, url: str) -> str:
    # OpenIM /object/<name> 自身返回 302 跳到 iDrive 直链,而 Groq/Gemini 都不 follow 重定向,
    # 必须由我们自行 fetch 后以 base64 inline 给 LLM。
    resp = await client.get(url)
    resp.raise_for_status()
    ctype = resp.headers.get("content-type", "").split(";")[0].strip()
    if not ctype.startswith("image/"):
        ext = Path(urlparse(url).path).suffix.lower().lstrip(".")
        ctype = _EXT_TO_MIME.get(ext, "image/jpeg")
    b64 = base64.b64encode(resp.content).decode("ascii")
    return f"data:{ctype};base64,{b64}"


async def image_message(image_urls: list[str], prompt: str = "") -> HumanMessage:
    """构造多模态 HumanMessage：自行 fetch 图片(follow 重定向)后以 base64 inline。"""
    content: list[dict] = []
    if prompt:
        content.append({"type": "text", "text": prompt})
    async with httpx.AsyncClient(follow_redirects=True, timeout=15.0) as client:
        data_urls = await asyncio.gather(
            *(_fetch_as_data_url(client, u) for u in image_urls)
        )
    for data_url in data_urls:
        content.append({"type": "image_url", "image_url": {"url": data_url}})
    return HumanMessage(content=content)
