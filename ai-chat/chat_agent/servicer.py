"""ChatAgent gRPC servicer — DH 的 AI 聊天回复。"""

import logging
import os
import grpc

from dating_proto_youjianxin_chat import chat_pb2, chat_pb2_grpc

from chat_agent.builder import build_agent, UserContext
from core.clients import UserServiceClient

logger = logging.getLogger(__name__)


class ChatAgentServicer(chat_pb2_grpc.ChatAgentServicer):
    """Handles ChatAgent gRPC requests."""

    def __init__(self, llm, user_client: UserServiceClient, checkpointer, reply_language: str | None = None):
        self._llm = llm
        self._user_client = user_client
        # 所有对话共享同一 checkpointer（thread_id 隔离）和同一 agent（@dynamic_prompt 注入）
        self._checkpointer = checkpointer
        # 全局语言覆盖：来自 DH_REPLY_LANGUAGE，None=遵循 system.md 的 English only
        self._reply_language = reply_language
        self._agent = build_agent(llm=self._llm, checkpointer=self._checkpointer)

    async def Chat(self, request: chat_pb2.ChatRequest, context) -> chat_pb2.ChatResponse:
        if not request.thread_id:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "thread_id is required")
        logger.info("Chat: thread_id=%s from=%s to=%s", request.thread_id, request.from_user_id, request.to_user_id)
        try:
            from_id = int(request.from_user_id)
            to_id = int(request.to_user_id)
        except ValueError:
            await context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                "from_user_id/to_user_id must be integers",
            )

        # 并行查询 BH（真实用户）和 DH（人设）资料
        # 注意：build_system_prompt 参数 from_user=DH，to_user=BH
        # （与 ChatRequest 中的 from/to 含义相反）
        try:
            bh_info, dh_info = await self._user_client.get_bh_and_dh_users(from_id, to_id)
            result = await self._agent.ainvoke(
                {"messages": [{"type": "human", "content": request.message}]},
                {
                    "configurable": {"thread_id": request.thread_id},
                    # 下面的是langsmith的配置项
                    "tags": [os.environ.get("ENV", "dev")],
                    "run_name": f"chat-{request.thread_id}",
                    "metadata": {
                        "thread_id": request.thread_id,
                        "user_id": request.from_user_id,
                    },
                },
                context=UserContext(
                    bh_info=bh_info,
                    dh_info=dh_info,
                    reply_language=self._reply_language,
                ),
            )
        except Exception:
            logger.exception("Chat failed thread_id=%s", request.thread_id)
            await context.abort(grpc.StatusCode.INTERNAL, "agent invocation failed")

        ai_message = result["messages"][-1]
        raw = ai_message.content
        if isinstance(raw, str):
            content = raw
        elif isinstance(raw, list) and raw:
            first = raw[0]
            content = first.get("text", "") if isinstance(first, dict) else str(first)
        else:
            content = ""

        return chat_pb2.ChatResponse(content=content)
