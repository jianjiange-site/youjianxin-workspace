# vision_agent 结构化输出改造

## 背景

`vision_agent` 的 `ScoreFace` / `AnalyzeProfilePhoto` 两个 RPC 目前靠 prompt 要求模型吐
JSON，再在 `servicer.py` 里手动剥 ` ```json ` 围栏 → `json.loads` → 容错兜底
（`_JSON_FENCE` / `_extract_json` / `_clamp_score`）。这套手动解析脆弱：模型多说一句话
就会整体解析失败回 0。

改用 LangChain 1.x 的 `create_agent(response_format=Schema)`，让模型输出直接绑定到
Pydantic schema，拿到带校验的对象，删掉手动 JSON 解析。

已验证（langchain 1.2.15）：

- `response_format`（传 Pydantic 类即 `AutoStrategy`）在 model 节点内完成解析，调用经过中间件
  `awrap_model_call`，**SmartRouter 的 groq/gemini 轮询 + 熔断回退照常生效**
  （`factory.py:1037-1117`、`awrap_model_call` 的 `request.override(model=...)`）。
- `result["structured_response"]` 返回 **Pydantic 实例**（`OutputToolBinding.parse -> SchemaT`），
  按 `obj.field` 访问。
- 无工具 agent 不增加额外往返。
- `Understand` RPC 仍是自由文本，不动。

## 改动

### 1. 新增 `vision_agent/schemas.py`

```python
from pydantic import BaseModel, Field, field_validator

class FaceScore(BaseModel):
    status: int = Field(description="overall status 0-100")
    appearance: int = Field(description="physical attractiveness 0-100")
    sexual_attractiveness_score: int = Field(description="sexual appeal 0-100")

    @field_validator("*", mode="before")
    @classmethod
    def _clamp(cls, v):
        # 复刻原 _clamp_score：夹紧到 [0,100]，非法值回退 0（而非抛错触发重试）
        try:
            return max(0, min(100, int(round(float(v)))))
        except (TypeError, ValueError):
            return 0

class ProfileAnalysis(BaseModel):
    analysis: str = Field(description="analysis string | not_human | anime_human")
```

> 用 `field_validator` 夹紧而非 `Field(ge=0, le=100)`，保持原「silent clamp」语义。

### 2. `vision_agent/builder.py`

`_build_vision_agent` 加可选参数并透传：

```python
def _build_vision_agent(system_md: str, response_format=None):
    ...
    return create_agent(
        model=models[0], tools=[], middleware=[router],
        system_prompt=system_md, response_format=response_format, checkpointer=None,
    )

def build_score_agent():
    return _build_vision_agent(_load_prompt("face_score_system.md"), FaceScore)
def build_profile_agent():
    return _build_vision_agent(_load_prompt("profile_photo_system.md"), ProfileAnalysis)
```

`build_understand_agent` 不变。新增 `from vision_agent.schemas import FaceScore, ProfileAnalysis`。

### 3. `vision_agent/servicer.py`

- `ScoreFace`：用 `result.get("structured_response")` 取 `FaceScore` 实例，None 时兜底回全 0。
- `AnalyzeProfilePhoto`：`result["structured_response"].analysis`，None 时兜底空串。
- 删除 `import json` / `import re`、`_JSON_FENCE`、`_extract_json`、`_clamp_score`。
- 保留 `_agent_text`（`Understand` 仍用）。

ScoreFace 改造后：
```python
score = result.get("structured_response")
if score is None:
    logger.warning("ScoreFace structured_response missing")
    score = FaceScore(status=0, appearance=0, sexual_attractiveness_score=0)
return vision_pb2.ScoreFaceResponse(
    status=score.status, appearance=score.appearance,
    sexual_attractiveness_score=score.sexual_attractiveness_score,
)
```

### 4. （可选）prompt 瘦身

`face_score_system.md` / `profile_photo_system.md` 里 "Return a SINGLE JSON object" /
"DO NOT include explanations" 等格式约束可删（结构化机制已强制）；字段语义描述保留。
本次先不动 prompt，避免一次改太多，验证通过后再视情况精简。

## 验证

1. `uv run python -c "from vision_agent.builder import build_score_agent, build_profile_agent; build_score_agent(); build_profile_agent()"` —— 确认 agent 能正常构建。
2. 起 gRPC server，用真实图片各跑 `ScoreFace` / `AnalyzeProfilePhoto` 几次：
   - 正常人脸图 → 三个分数合理、analysis 字符串正常；
   - 非人类图（动物/风景）→ 全 0 / `not_human`；
   - 确认 groq 与 gemini 轮询都能返回结构化结果（看日志 SmartRouter 选了哪个模型）。
3. 关注 gemini 多模态 + 结构化输出是否稳定，个别端点对 tool-calling 结构化偶有不稳。
