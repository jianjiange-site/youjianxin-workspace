import os

import httpx
from pydantic import BaseModel, Field
from langchain_core.tools import tool

# Jina Reader：GET https://r.jina.ai/<完整url>，把网页抽取成干净正文返回。
# 无需 API key 即可使用（按 IP 限流）；配 JINA_API_KEY 仅为提高限流额度。
# 注意 Jina 本身较慢，故仅作「按需深读」工具，不在搜索热路径上自动调用。
_JINA_URL = "https://r.jina.ai/"
_JINA_KEY_ENV = "JINA_API_KEY"
_READ_TIMEOUT = 15.0
# 正文字符上限，避免单页全文撑爆上下文 / token。
_READ_MAX_CHARS = 4000


class ReadUrlInput(BaseModel):
    """Input schema for the read_url tool."""

    url: str = Field(description="The full URL to fetch and read, including https://")


@tool(args_schema=ReadUrlInput)
async def read_url(url: str) -> str:
    """Fetch a web page and return its main text content.

    Use only when you need the full content of a specific page (e.g. an article
    a web_search result pointed to). Slower than web_search — use sparingly.
    """
    headers = {
        "X-Return-Format": "text",  # 去掉 markdown 修饰，输出最紧凑
        "X-Engine": "direct",       # 走轻量引擎，优先速度（不渲染 JS）
        "X-Timeout": "12",          # 限制 Jina 端的页面加载等待
    }
    # 有 key 则提高限流额度；无 key 也能用，故为可选。
    api_key = os.environ.get(_JINA_KEY_ENV)
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    try:
        async with httpx.AsyncClient(
            timeout=_READ_TIMEOUT,
            follow_redirects=True,
        ) as client:
            resp = await client.get(_JINA_URL + url, headers=headers)
            resp.raise_for_status()
            text = resp.text
    except httpx.HTTPError:
        # 网络/HTTP 异常一律降级为友好文案，避免把异常抛给 LLM 当 tool error
        return "Sorry, I couldn't read that page right now."

    text = text.strip()
    if not text:
        return "That page didn't return any readable content."
    return text[:_READ_MAX_CHARS]
