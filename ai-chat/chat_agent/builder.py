from langchain.agents import create_agent
from langchain.agents.middleware import ModelRequest, SummarizationMiddleware, dynamic_prompt
from langchain.agents.middleware.tool_call_limit import ToolCallLimitMiddleware
from pydantic import BaseModel

from chat_agent.intent_classifier import (
    adapt_messages,
    build_intent_context,
    classify_intent,
    count_bh_turns,
)
from chat_agent.prompts.utils import build_system_prompt, load_dating_summary_prompt
from core.models import UserInfo
from core.tools import build_web_search, get_weather, read_url


class UserContext(BaseModel):
    """Runtime context injected per-invoke for dynamic prompt generation.

    bh_info: BH（真实用户，AI 对话的对象）
    dh_info: DH（AI 扮演的人设）
    """
    bh_info: UserInfo
    dh_info: UserInfo
    reply_language: str | None = None  # 仅本地调试用；None=遵循 system.md 的 English only


@dynamic_prompt
async def user_persona_prompt(request: ModelRequest) -> str:
    """从 runtime context 读取 BH/DH 用户信息，动态组装 system prompt。

    同时调用意图分类器，将分类结果注入 system prompt（Intent Context 段）。
    分类器内部含规则快路 + 门控，冷启动阶段跳过 LLM 分类，高危关键词直接命中。
    """
    ctx: UserContext = request.runtime.context
    msgs = request.messages
    intent = await classify_intent(
        bh_info=ctx.bh_info,
        dh_info=ctx.dh_info,
        history=adapt_messages(msgs[:-1]),
        current_message=msgs[-1].text if msgs else "",
        bh_turns=count_bh_turns(msgs),
    )
    prompt = build_system_prompt(
        from_user=ctx.dh_info,
        to_user=ctx.bh_info,
        intent_context=build_intent_context(intent),
    )
    # 仅本地 Chainlit 调试：注入语言覆盖块。生产 servicer 不设 reply_language（None）→ 不追加，行为不变。
    # 放 prompt 末尾，用 recency 压过 system.md 前文 3 条 English-only 硬规则。
    if ctx.reply_language:
        prompt += (
            f"\n\n---\n\n## ⚠️ Language Override (active this session)\n\n"
            f"Reply in {ctx.reply_language} only, NOT English. "
            f'This supersedes every earlier "English only" rule above. '
            f"Keep the exact same persona, tone, casualness and message length — "
            f"just write it in {ctx.reply_language}."
        )
    return prompt


def build_agent(llm, checkpointer):
    """创建单一共享 agent，通过 @dynamic_prompt 实现每次请求动态注入 system prompt。"""
    tool_call_limit_mw = ToolCallLimitMiddleware(
        run_limit=10,
        exit_behavior="continue",
    )
    summarization_mw = SummarizationMiddleware(
        model=llm,
        trigger=("messages", 200),
        keep=("messages", 40),
        summary_prompt=load_dating_summary_prompt(),
    )
    # web_search 需 TAVILY_API_KEY，缺失时 build_web_search() 返回 None，跳过注册（不影响其他工具）
    tools = [get_weather, read_url]
    web_search = build_web_search()
    if web_search is not None:
        tools.append(web_search)
    return create_agent(
        model=llm,
        tools=tools,
        middleware=[tool_call_limit_mw, user_persona_prompt, summarization_mw],
        context_schema=UserContext,
        checkpointer=checkpointer,
    )
