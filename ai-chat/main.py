import chainlit as cl
from dotenv import load_dotenv

load_dotenv()

import os

from chat_agent.builder import build_agent, UserContext
from core.llm import build_chat_llm
from core.models import UserInfo
from core.time_utils import current_local_time
from langgraph.checkpoint.memory import InMemorySaver

# Mock data for local prompt testing only
_MOCK_DH = UserInfo(
    nickname="Sandy",
    age=24,
    gender="Female",
    height=165,
    bio="Graphic designer, loves photography and indie music. A bit sarcastic, very honest.",
    occupation="Graphic designer",
    education="BFA, Visual Communication",
    location="LA",
    birthday="2002-03-15",
    interests=[
        {"tab_key": "music", "tag_key": "indie"},
        {"tab_key": "hobby", "tag_key": "photography"},
    ],
    city="Los Angeles",
    state_code="CA",
    race="White",
    current_time=current_local_time(34.0522, -118.2437, "CA"),
)
_MOCK_BH = UserInfo(
    nickname="Alex",
    age=26,
    gender="Male",
    height=180,
    bio="Software engineer, into hiking and sci-fi movies. Quiet but thoughtful.",
    occupation="Software engineer",
    education="BSc, Computer Science",
    location="Beijing",
    birthday="2000-07-22",
    interests=[
        {"tab_key": "outdoor", "tag_key": "hiking"},
        {"tab_key": "movie", "tag_key": "sci-fi"},
    ],
    city="Beijing",
    state_code="",
    race="Asian",
    current_time=current_local_time(39.9042, 116.4074, ""),
)

_checkpointer = InMemorySaver()
_agent = build_agent(llm=build_chat_llm(), checkpointer=_checkpointer)
# 本地调试用：DH_REPLY_LANGUAGE=Chinese 让 DH 改说中文；留空=英文。仅本地，生产不读此路径。
_reply_language = os.getenv("DH_REPLY_LANGUAGE") or None
_context = UserContext(bh_info=_MOCK_BH, dh_info=_MOCK_DH, reply_language=_reply_language)


@cl.on_message
async def on_message(message: cl.Message):
    # 每个 chainlit 连接独立的 session id 当 thread_id，避免多用户共享同一段对话历史
    thread_id = cl.context.session.id

    res = await _agent.ainvoke(
        {"messages": [{"type": "human", "content": message.content}]},
        {"configurable": {"thread_id": thread_id}},
        context=_context,
    )

    ai_message = res["messages"][-1]
    content = (
        ai_message.content
        if isinstance(ai_message.content, str)
        else ai_message.content[0].get("text", "")
    )

    await cl.Message(content=content).send()
