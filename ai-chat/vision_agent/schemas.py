"""vision agent 结构化输出 schema：供 create_agent(response_format=...) 绑定。"""

from pydantic import BaseModel, Field, field_validator


class FaceScore(BaseModel):
    """颜值打分结果，三个维度均为 0-100。"""

    status: int = Field(description="overall status 0-100")
    appearance: int = Field(description="physical attractiveness 0-100")
    sexual_attractiveness_score: int = Field(description="sexual appeal 0-100")

    @field_validator("*", mode="before")
    @classmethod
    def _clamp(cls, v):
        """夹紧到 [0, 100]，非法值回退 0（silent clamp，不抛错触发重试）。"""
        try:
            return max(0, min(100, int(round(float(v)))))
        except (TypeError, ValueError):
            return 0


class ProfileAnalysis(BaseModel):
    """头像分析结果：描述字符串 / not_human / anime_human。"""

    analysis: str = Field(description="analysis string | not_human | anime_human")
