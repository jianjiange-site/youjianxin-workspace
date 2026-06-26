"""VisionAgent gRPC servicer：图像理解 / 颜值打分 / 头像分析。"""

import logging

import grpc

from dating_proto_youjianxin_vision import vision_pb2, vision_pb2_grpc

from vision_agent.builder import (
    build_profile_agent,
    build_score_agent,
    build_understand_agent,
    image_message,
)
from vision_agent.schemas import FaceScore

logger = logging.getLogger(__name__)


def _agent_text(result) -> str:
    """从 agent 结果取最后一条消息的纯文本。"""
    msg = result["messages"][-1]
    raw = msg.content
    if isinstance(raw, str):
        return raw
    if isinstance(raw, list) and raw:
        first = raw[0]
        return first.get("text", "") if isinstance(first, dict) else str(first)
    return ""


class VisionAgentServicer(vision_pb2_grpc.VisionAgentServicer):
    """Handles VisionAgent gRPC requests."""

    def __init__(self):
        self._understand = build_understand_agent()
        self._score = build_score_agent()
        self._profile = build_profile_agent()

    async def Understand(self, request, context) -> vision_pb2.UnderstandResponse:
        image_urls = list(request.image_urls)
        if not image_urls:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "image_urls is required")
        prompt = request.prompt or "Describe the key elements of this image."
        try:
            result = await self._understand.ainvoke(
                {"messages": [await image_message(image_urls, prompt)]}
            )
        except Exception:
            logger.exception("Understand failed")
            await context.abort(grpc.StatusCode.INTERNAL, "vision understand failed")
        return vision_pb2.UnderstandResponse(content=_agent_text(result))

    async def ScoreFace(self, request, context) -> vision_pb2.ScoreFaceResponse:
        image_urls = list(request.image_urls)
        if not image_urls:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "image_urls is required")
        try:
            result = await self._score.ainvoke(
                {"messages": [await image_message(image_urls, "Evaluate the image(s) per your instructions.")]}
            )
        except Exception:
            logger.exception("ScoreFace failed")
            await context.abort(grpc.StatusCode.INTERNAL, "vision score failed")

        score = result.get("structured_response")
        if score is None:
            logger.warning("ScoreFace structured_response missing, raw=%r", _agent_text(result))
            score = FaceScore(status=0, appearance=0, sexual_attractiveness_score=0)
        return vision_pb2.ScoreFaceResponse(
            status=score.status,
            appearance=score.appearance,
            sexual_attractiveness_score=score.sexual_attractiveness_score,
        )

    async def AnalyzeProfilePhoto(
        self, request, context
    ) -> vision_pb2.AnalyzeProfilePhotoResponse:
        if not request.image_url:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "image_url is required")
        try:
            result = await self._profile.ainvoke(
                {"messages": [await image_message([request.image_url], "Analyze this profile photo per your instructions.")]}
            )
        except Exception:
            logger.exception("AnalyzeProfilePhoto failed")
            await context.abort(grpc.StatusCode.INTERNAL, "vision profile analysis failed")

        analysis = result.get("structured_response")
        return vision_pb2.AnalyzeProfilePhotoResponse(
            analysis=analysis.analysis.strip() if analysis else ""
        )
