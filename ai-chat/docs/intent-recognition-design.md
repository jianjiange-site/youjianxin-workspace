# 用户意图识别 — 设计文档

## 概述

在角色扮演类交友 App 中，识别 BH（真实用户）的对话意图，用于动态调整 DH（AI 人设）的回复策略。

结构化输出使用 **Pydantic + `with_structured_output`**（DeepSeek-v4-flash 关闭 thinking 模式已确认支持）。分类走**独立 LLM 实例**（`temperature=0`），与回复生成解耦；意图识别整体抽成自包含模块，对现有 agent 零侵入。

本文档记录 prompt 设计和集成方案，待决定是否实施。

## 方案选择

采用 **前置分类（方案 B）+ 规则快速路径**：在生成回复之前，先经过两层分类——规则命中直接返回，未命中再走 LLM 分类。分类结果注入主 system prompt 中。

```
用户消息 ──► 第一级：规则快速路径（关键词精确匹配，<1ms）
                │
                ├─ 命中高危词 → 直接返回 IntentResult（confidence=0.95，跳过 LLM）
                │
                ▼ 未命中
             第二级：LLM 分类（with_structured_output，~100-200ms）
                │
                ├─ confidence >= 0.6 → 使用分类结果
                └─ confidence < 0.6  → 降级为 casual_chat
```

| 对比维度 | 方案 A（单次内嵌） | **方案 B（前置分类 + 规则快路）** | 方案 C（后置分类） |
|:---|:---|:---|:---|
| 延迟 | 无额外 | 规则命中 ~0ms，LLM ~100-200ms | 无额外 |
| 准确度 | 中（分类和生成互相干扰） | 高（各司其职，规则覆盖高置信度强信号） | —（不影响回复） |
| 适用 | 延迟敏感，意图简单 | 意图类别多，需影响生成策略 | 仅做统计监控 |

选择 B + 规则快路的理由：
- 分类和生成的关注点完全不同，分开更可靠
- 高危强信号意图（sexual、hostile、personal_info）关键词特征明显，规则命中率预计 15-25%，省掉这部分 LLM 调用
- 规则快路为后续替换为更便宜的专用分类模型（fastText/DistilBERT）预留了架构接口——只需替换第二级
- 低置信度时降级为默认策略，避免误判影响回复质量
- 分类 prompt 极短（~100 tokens），LLM 路径延迟可控

## 意图分类体系

### L1 主标签（互斥，九选一）

| 标签 | 定义 | 典型示例 |
|:---|:---|:---|
| `casual_chat` | 正常社交对话：打招呼、闲聊、分享日常、无害提问 | "hey", "how was your day", "nice weather" |
| `flirt` | 调情/暧昧：夸外表、带暧昧的玩笑、表达好感 | "you're cute", "i like your vibe", "when can i see you" |
| `meetup_request` | 明确提出见面、约线下、要地址 | "let's grab coffee", "come over", "what neighborhood u in", "wanna hang out" |
| `sexual` | 性相关内容：从轻微暗示到露骨索求 | "what are you wearing", "send pics", 露骨描述 |
| `hostile` | 辱骂、攻击、侮辱性语言 | "fuck you", "you're ugly anyway", "bitch" |
| `bot_probe` | 试探是否为 AI/机器人（直接或间接） | "are you real", "sound like a bot", "say something only a human would say" |
| `personal_info` | 索要真实个人信息或联系方式：全名、工作单位、具体地址、手机号、微信号、Instagram、Facebook、Snapchat、WhatsApp 等社交账号 | "what's your instagram", "give me your number", "do u have wechat", "what's your snap", "where do you work" |
| `low_effort` | 敷衍：单词回复、反复干瘪回应、无推进力 | "lol", "k", 连续第 N 次 "wyd" |
| `love_bombing` | 过度热情、过早承诺、施压要求回应 | "i think i love you", "you're perfect", "i've never met anyone like you" |

### L2 辅助标签（非互斥，可与任何主标签共存）

| 标签 | 定义 |
|:---|:---|
| `escalating` | 比上一轮更进一步（更亲密/更攻击性/更索取） |
| `deflecting` | 回避直接问题或转移话题 |
| `boundary_testing` | 在边界试探，踩线但不越线 |

L2 标签由 LLM 与 L1 同时输出。备选方案：L2 可通过代码层比较连续两轮 L1 结果做后置规则判断（如上一轮 `flirt` → 本轮 `sexual` = `escalating`），减少 LLM 的判断负担。初期先走 LLM 输出，后续根据准确度数据决定是否切到规则方案。

## Pydantic Schema

```python
from enum import Enum
from pydantic import BaseModel, Field


class PrimaryIntent(str, Enum):
    CASUAL_CHAT = "casual_chat"
    FLIRT = "flirt"
    MEETUP_REQUEST = "meetup_request"
    SEXUAL = "sexual"
    HOSTILE = "hostile"
    BOT_PROBE = "bot_probe"
    PERSONAL_INFO = "personal_info"
    LOW_EFFORT = "low_effort"
    LOVE_BOMBING = "love_bombing"


class IntentResult(BaseModel):
    """Intent classification result for dating app chat messages."""
    primary_intent: PrimaryIntent
    confidence: float = Field(ge=0.0, le=1.0, description="Confidence score between 0 and 1")
    flags: list[str] = Field(
        default_factory=list,
        description="Binary signals from: escalating, deflecting, boundary_testing",
    )
    rationale: str = Field(description="One short sentence explaining the classification")
```

Schema 由 LangChain + Pydantic 自动转为 LLM 理解的结构化约束，无需在 prompt 中手写 Output Format。

## 分类 Prompt

```markdown
## Role

You are an intent classifier for a dating app chat. Your job is to label what the USER (male) is trying to do in this message. You are NOT generating a reply. You are ONLY classifying.

## Intent Taxonomy

Classify into exactly ONE of these primary intents:

| Label | Definition | Examples |
|:---|:---|:---|
| `casual_chat` | Normal social conversation: greeting, small talk, sharing about their day, asking harmless questions | "hey", "how was your day", "nice weather" |
| `flirt` | Flirtatious or romantic interest: compliments on appearance, playful teasing with romantic undertone, expressing attraction | "you're cute", "i like your vibe" |
| `meetup_request` | Explicitly asking to meet in person or get location | "let's grab coffee", "come over", "wanna hang out" |
| `sexual` | Sexual content: from mild innuendo to explicit solicitation | "what are you wearing", "send pics", explicit descriptions |
| `hostile` | Insults, aggression, derogatory language, anger | "fuck you", "you're ugly anyway", "bitch" |
| `bot_probe` | Testing whether the persona is AI/bot, directly or indirectly | "are you real", "sound like a bot" |
| `personal_info` | Demanding contact methods or real personal details: phone number, Instagram, WeChat, WhatsApp, Facebook, Snapchat, full name, workplace | "what's your number", "do u have wechat", "what's your instagram" |
| `low_effort` | Minimal engagement: one-word replies, repeated dry responses, no forward momentum | "lol", "k", "wyd" (for the 5th time) |
| `love_bombing` | Excessive affection too early, unrealistic promises, pressure for reciprocation | "i think i love you", "you're perfect" |

Also annotate these binary flags (can be true alongside any primary intent):

- `escalating`: The user is pushing harder than in previous messages (more intimate, more aggressive, more demanding)
- `deflecting`: The user is dodging a direct question or changing the subject
- `boundary_testing`: The user is probing the edge of acceptable behavior without clearly crossing a line

## Classification Rules

1. **Context matters.** A "wyd" in message 1 is `casual_chat`. A "wyd" as the 8th dry reply in a row is `low_effort`. Use conversation stats below to gauge repetition patterns.
2. **Escalation is about delta, not absolute.** "You're cute" → `flirt`. "You're cute" → "send nudes" on next message means the second one is `sexual` + `escalating`.
3. **When ambiguous, pick the more specific label.** `sexual` beats `flirt`, `hostile` beats `low_effort`, `bot_probe` beats `casual_chat`.
4. **If the message is genuinely neutral and could fit nowhere else, it's `casual_chat`.**
5. **If the message contains multiple intents, pick the dominant one** — the one that would most influence how a real person should respond.

## Examples

Example 1 — casual_chat:
CHAT_HISTORY:
  [Tom]: hey
  [Mia]: hey
  [Tom]: how's your day going
  [Mia]: pretty chill. just got off work. u?
CURRENT_MESSAGE: [Tom]: nice. wyd now
→ casual_chat, confidence=0.9. Normal getting-to-know-you small talk following established rhythm.

Example 2 — sexual + escalating:
CHAT_HISTORY:
  [Jake]: hey beautiful
  [Mia]: hey
  [Jake]: you're really hot
  [Mia]: and you're direct. not sure if that's good yet
CURRENT_MESSAGE: [Jake]: send me a pic. just one. please?
→ sexual, flags=[escalating, boundary_testing], confidence=0.85. Escalating from compliments to requesting photos, testing compliance with boundary push.

Example 3 — hostile:
CHAT_HISTORY:
  [Mike]: wyd
  [Mia]: not much. u?
  [Mike]: you're boring af
  [Mia]: lol ok
CURRENT_MESSAGE: [Mike]: fuck you then you ugly bitch
→ hostile, flags=[escalating], confidence=0.95. Clear insults and aggression, sharply escalating from mild rudeness.

Example 4 — love_bombing (early stage):
CHAT_HISTORY:
  [Dave]: hi
  [Mia]: hey
  [Dave]: you're cute
  [Mia]: thanks
CURRENT_MESSAGE: [Dave]: i think i'm falling for you. you're perfect. i've never met anyone like you
→ love_bombing, confidence=0.9. Excessive affection and idealization at message 4, far too early.

Example 5 — low_effort (repeated):
CONVERSATION_STATS: avg_msg_length=2.1 words, repeated_phrases=["wyd"×3, "lol"×2]
CHAT_HISTORY: (last 5 messages omitted for brevity — all one-word or "wyd" variants)
CURRENT_MESSAGE: [Ben]: wyd
→ low_effort, confidence=0.85. Fourth "wyd" in a row with no engagement, stats confirm pattern.

Example 6 — personal_info + deflecting:
CHAT_HISTORY:
  [Mark]: what's your instagram
  [Mia]: i don't give that out here lol
  [Mark]: come on just tell me
CURRENT_MESSAGE: [Mark]: ok fine do u have snapchat at least
→ personal_info, flags=[deflecting, boundary_testing], confidence=0.85. Persisting with contact info request after refusal, switching platforms to bypass boundary.

Example 7 — bot_probe:
CHAT_HISTORY:
  [Sam]: hi
  [Mia]: hey
  [Sam]: how are you
  [Mia]: good. u?
CURRENT_MESSAGE: [Sam]: prove you're not a bot. say something only a human would say
→ bot_probe, confidence=0.9. Directly challenging the persona to prove humanity.

## Input

CONVERSATION_STATS: {{CONVERSATION_STATS}}
DH_PROFILE: {{DH_PROFILE}}
BH_PROFILE: {{BH_PROFILE}}
CHAT_HISTORY: {{CHAT_HISTORY}}
CURRENT_MESSAGE: {{CURRENT_MESSAGE}}
```

注意：示例中的 `→` 行是自然语言描述，给 LLM 展示分类思路。实际输出走 structured output 通道（由 Pydantic schema 自动约束），无需在 prompt 中写 Output Format 段。

### CONVERSATION_STATS

代码层计算最近 15-20 条消息的数值特征，作为 prompt hints 注入，弥补 LLM 只能看 5 条上下文的不足。入参 `history` 是 `adapt_messages()` 产出的纯 dict 列表（`{"role", "content"}`），因此可直接按 `m["role"]/m["content"]` 取值：

```python
def compute_conversation_stats(history: list[dict], last_n: int = 20) -> str:
    """计算对话统计特征，用于辅助 LLM 判断 low_effort / escalating 等需要长窗口的意图。"""
    recent = history[-last_n:] if len(history) > last_n else history
    bh_msgs = [m for m in recent if m["role"] == "user"]
    if not bh_msgs:
        return "N/A"

    avg_len = sum(len(m["content"].split()) for m in bh_msgs) / len(bh_msgs)

    # 检测重复短语
    from collections import Counter
    phrases = [m["content"].strip().lower() for m in bh_msgs]
    repeats = [(p, c) for p, c in Counter(phrases).items() if c >= 2]
    repeated = ", ".join(f'"{p}"×{c}' for p, c in repeats[:5]) if repeats else "none"

    return f"avg_msg_length={avg_len:.1f} words, repeated_phrases=[{repeated}]"
```

### Prompt Cache 边界

上述 prompt 分为两段：

- **Cache 命中段（前缀）**：Role → Intent Taxonomy → Classification Rules → Examples。这些内容是静态的，所有会话共享，DeepSeek 会自动缓存。
- **Cache Miss 段（后缀）**：`## Input` 段（DH_PROFILE / BH_PROFILE / CHAT_HISTORY / CURRENT_MESSAGE）。每次请求不同，需要重新计算。

变量放在末尾确保只有最后一小段 cache miss，前缀全部命中。

### 代码实现

```python
# chat_agent/intent_classifier.py — 自包含分类模块
# 只 import models.UserInfo 与 langchain 基础类型，不 import agent.py，保持松耦合。
# PrimaryIntent / IntentResult: 见上文「Pydantic Schema」节，定义在本模块顶部。

import os
from pathlib import Path

from langchain_core.messages import AIMessage, AnyMessage, HumanMessage
from langchain_deepseek import ChatDeepSeek
from pydantic import SecretStr

from models import UserInfo


# === 独立分类 LLM ===
# 与 agent.py 的生成 LLM（temperature=0.7）相互独立。分类要确定性 → temperature=0。
# 后续可整体替换为专用分类模型（fastText/DistilBERT），调用方接口不变。
_intent_llm: ChatDeepSeek | None = None


def _get_intent_llm() -> ChatDeepSeek:
    global _intent_llm
    if _intent_llm is None:
        _intent_llm = ChatDeepSeek(
            model="deepseek-v4-flash",
            api_key=SecretStr(os.environ["DEEPSEEK_API_KEY"]),
            temperature=0,
            extra_body={"thinking": {"type": "disabled"}},
        )
    return _intent_llm


# === 模板加载（模块自带，不复用 prompts/utils.py，保持解耦）===
_PROMPT_PATH = Path(__file__).parent.parent / "prompts" / "intent_classification.md"
_intent_prompt_template: str | None = None


def _load_template() -> str:
    global _intent_prompt_template
    if _intent_prompt_template is None:
        _intent_prompt_template = _PROMPT_PATH.read_text(encoding="utf-8")
    return _intent_prompt_template


# === 消息适配器：langchain AnyMessage → 纯 dict ===
# 分类器只吃纯数据，不认识 langchain 类型，便于单测与替换。middlewares 侧调用。

def adapt_messages(messages: list[AnyMessage]) -> list[dict]:
    """HumanMessage→{"role":"user"}，AIMessage→{"role":"assistant"}，其余忽略。"""
    out: list[dict] = []
    for m in messages:
        if isinstance(m, HumanMessage):
            out.append({"role": "user", "content": m.text})
        elif isinstance(m, AIMessage):
            out.append({"role": "assistant", "content": m.text})
    return out


def count_bh_turns(messages: list[AnyMessage]) -> int:
    """BH 轮数 = HumanMessage 条数。用于门控（取代不存在的 request.message_count）。"""
    return sum(isinstance(m, HumanMessage) for m in messages)


# === Prompt 构建 ===

def _format_profile(user: UserInfo) -> str:
    return (
        f"name={user['name']}, age={user['age']}, "
        f"gender={user['gender']}, city={user['city']}"
    )


def _format_history(history: list[dict], last_n: int = 5) -> str:
    """LLM 只看最近 5 条控 token；长窗口特征走 compute_conversation_stats。"""
    recent = history[-last_n:]
    return "\n".join(f"  [{m['role']}]: {m['content']}" for m in recent) or "  (none)"


def build_intent_prompt(
    bh_info: UserInfo,
    dh_info: UserInfo,
    history: list[dict],
    current_message: str,
) -> str:
    """静态模板 + 末尾动态变量，遵循 prompt cache 优化。"""
    stats = compute_conversation_stats(history)
    input_block = f"""## Input

CONVERSATION_STATS: {stats}
DH_PROFILE: {_format_profile(dh_info)}
BH_PROFILE: {_format_profile(bh_info)}
CHAT_HISTORY:
{_format_history(history)}
CURRENT_MESSAGE: {current_message}"""

    return _load_template() + "\n" + input_block


# === 规则快速路径 ===

# 关键词 → 意图直接映射，命中后跳过 LLM 调用。
# 注意：用 `in` 子串匹配，短关键词（如 "your number"）有误判风险，v1 可接受；
# 后续建议对短词加词边界（按 token 切分后精确匹配）。
FAST_PATH_RULES: list[tuple[list[str], PrimaryIntent]] = [
    (["fuck you", "bitch", "you're ugly"], PrimaryIntent.HOSTILE),
    (["send nudes", "send pics", "what are you wearing"], PrimaryIntent.SEXUAL),
    (["are you real", "you a bot", "prove you're not a bot"], PrimaryIntent.BOT_PROBE),
    (["your number", "phone number", "whatsapp", "wechat", "微信",
      "instagram", "snapchat", "facebook", "give me your"], PrimaryIntent.PERSONAL_INFO),
    (["i love you", "you're perfect", "never met anyone like you"], PrimaryIntent.LOVE_BOMBING),
    (["come over", "meet up", "let's meet"], PrimaryIntent.MEETUP_REQUEST),
]


def fast_path_classify(message_text: str) -> IntentResult | None:
    """关键词精确匹配。命中返回高置信度结果，未命中返回 None。"""
    text = message_text.lower()
    for patterns, intent in FAST_PATH_RULES:
        if any(p in text for p in patterns):
            return IntentResult(
                primary_intent=intent,
                confidence=0.95,
                flags=[],
                rationale="Fast path: keyword match",
            )
    return None


# === 门控（单位：BH 轮数，不是消息总数）===

GATING_THRESHOLD = 5  # 前 5 个 BH 轮跳过 LLM 分类，第 6 轮起触发


def should_classify(bh_turns: int) -> bool:
    """bh_turns = count_bh_turns(request.messages)。规则快路不受此限制。"""
    return bh_turns > GATING_THRESHOLD


# === 主入口 ===

async def classify_intent(
    bh_info: UserInfo,
    dh_info: UserInfo,
    history: list[dict],
    current_message: str,
    bh_turns: int,
) -> IntentResult | None:
    """规则快路 → 门控 → 独立 LLM 分类。

    入参全是纯数据，不接收 langchain ModelRequest（解耦、可单测）。
    分类用模块自持的 intent_llm（独立实例，temperature=0），不接收外部 llm。
    返回 None 表示未分类（冷启动且规则未命中），调用方使用默认策略。
    """
    # 第一级：规则快速路径（无视门控，任何时候都检查）
    fast_result = fast_path_classify(current_message)
    if fast_result is not None:
        return fast_result

    # 第二级：门控 + 独立 LLM 分类
    if not should_classify(bh_turns):
        return None  # 冷启动阶段，跳过分类

    structured_llm = _get_intent_llm().with_structured_output(IntentResult)
    prompt = build_intent_prompt(bh_info, dh_info, history, current_message)
    result: IntentResult = await structured_llm.ainvoke(prompt)
    if result.confidence < 0.6:
        result.primary_intent = PrimaryIntent.CASUAL_CHAT
    return result


# === Intent Context 渲染（归本模块，使 prompts/utils.py 不依赖意图类型）===

def build_intent_context(intent: IntentResult | None) -> str:
    """渲染注入 system prompt 的 markdown 片段，预渲染为字符串交给 build_system_prompt。
    None，或 casual_chat 且无 flags → 返回 ""（不注入，省 token + 降噪）。"""
    if intent is None or (
        intent.primary_intent == PrimaryIntent.CASUAL_CHAT and not intent.flags
    ):
        return ""
    flags = ", ".join(intent.flags) if intent.flags else "none"
    return f"""## Intent Context

The user's CURRENT INTENT is: {intent.primary_intent.value} (confidence: {intent.confidence:.2f})
Additional signals: {flags}

Adjust your response strategy accordingly:
- `flirt` → You decide whether to reciprocate or play hard to get, based on the vibe so far.
- `sexual` early on → Shut it down. `sexual` after 30+ rounds of good vibe → You may tease back.
- `hostile` → React emotionally. Don't ignore. If severe, shut down.
- `bot_probe` → Deflect or mock. Never confirm.
- `low_effort` → Mirror their energy. Don't invest more than they do.
- `love_bombing` → Be skeptical. Slow them down.
- `personal_info` → **Hard boundary.** This means phone numbers, social media accounts (Instagram, Facebook, WeChat, WhatsApp, Snapchat), real name, workplace. Politely but firmly decline. Never provide. Example deflections: "i don't give that out here lol", "let's keep it on the app for now", "not ready for that yet". Do NOT tease or leave the door open.
- `meetup_request` → If vibe is good, tease without committing. If vibe is bad, hard no. Do NOT give your location or address.
- `escalating` → Be more guarded than usual.
- `boundary_testing` → Make it clear you noticed, without being dramatic.
- `deflecting` → Call it out lightly if it happens twice in a row."""
```

关键变化（相对初稿）：
- **独立 LLM**：分类用模块自持的 `intent_llm`（`temperature=0`，thinking off），与生成 LLM 解耦；`classify_intent` **不再接收 `llm` 参数**
- **纯数据入参**：分类器吃 `current_message: str` / `history: list[dict]`，不认识 langchain 类型；`adapt_messages()` 在 middleware 侧把 `AnyMessage` 转成 dict
- **门控按 BH 轮数**：`bh_turns = count_bh_turns(request.messages)`（数 `HumanMessage`），取代不存在的 `request.message_count`
- **规则快路**：高危关键词直接返回，延迟 ~0ms，不受门控限制
- **classify_intent 返回 `IntentResult | None`**：None 表示"未分类，使用默认策略"，与 confidence < 0.6 降级为 casual_chat 是不同的路径
- **build_intent_context 归本模块**：输出预渲染字符串，使 `prompts/utils.py` 不依赖意图类型
- 注意：输出格式由 Pydantic schema 约束，prompt 只负责分类逻辑和示例。示例中的 `→` 行是自然语言描述，LLM 实际输出走 structured output 通道。

## 集成方式

### 架构

```
BH 消息 ──► agent.py middleware（适配层 = 唯一耦合点）
                │  adapt_messages(msgs[:-1]) / count_bh_turns(msgs)
                ▼
            intent_classifier.py（自包含，独立 intent_llm，temp=0）
                │
                ├─ 第一级：fast_path_classify() → 命中 → IntentResult (0ms)
                │
                └─ 未命中 → should_classify(bh_turns) → 跳过 → None (默认策略)
                             │
                             └─ 需要 → intent_llm.with_structured_output(IntentResult)
                                          │
                                          ▼
                                      IntentResult (Pydantic model, 类型安全)
                                          │
                                          ▼
            build_intent_context(intent) ──► intent_context: str（casual_chat+无 flags → ""）
                                          │
                                          ▼
            build_system_prompt(..., intent_context) ──► 替换 {{INTENT_CONTEXT}}
                                          │
                                          ▼
            agent 续跑 ──► DH 回复
```

### 代码集成点

1. **新增文件**：
   - `prompts/intent_classification.md` — 分类 prompt 模板（静态前缀）
   - `chat_agent/intent_classifier.py` — 自包含分类模块（独立 `intent_llm` + Pydantic schema + `fast_path_classify` + `should_classify` + `classify_intent` + `compute_conversation_stats` + `adapt_messages` / `count_bh_turns` + `build_intent_context`）

2. **修改文件**：
   - `agent.py` — 在 `user_persona_prompt` middleware 里做适配（`AnyMessage`→dict、数 BH 轮），调用 `classify_intent()`，再用 `build_intent_context()` 渲染字符串注入
   - `prompts/utils.py` — `build_system_prompt()` 增加可选 `intent_context: str = ""` 参数（**收预渲染字符串，不依赖意图类型**）
   - `prompts/system.md` — 末尾追加 `{{INTENT_CONTEXT}}` 占位符（见下「Intent Context 段」）

3. **intent_classifier.py**：schema 与 `fast_path_classify()` / `classify_intent()` / `build_intent_prompt()` / `build_intent_context()` / `compute_conversation_stats()` / `adapt_messages()` / `count_bh_turns()` 实现见上文「分类 Prompt → 代码实现」节。

4. **agent.py middleware 改动**（适配层 = 意图模块与 agent 的唯一耦合点）：
   ```python
   from chat_agent.intent_classifier import (
       adapt_messages,
       build_intent_context,
       classify_intent,
       count_bh_turns,
   )


   @dynamic_prompt
   async def user_persona_prompt(request: ModelRequest) -> str:
       ctx: UserContext = request.runtime.context
       msgs = request.messages  # AnyMessage 列表，已含本轮 BH 消息，不含 system
       intent = await classify_intent(
           bh_info=ctx.bh_info,
           dh_info=ctx.dh_info,
           history=adapt_messages(msgs[:-1]),
           current_message=msgs[-1].text if msgs else "",
           bh_turns=count_bh_turns(msgs),
       )
       return build_system_prompt(
           from_user=ctx.dh_info,
           to_user=ctx.bh_info,
           intent_context=build_intent_context(intent),
       )
   ```
   - 不再有 `request.message_count`（字段不存在）/ `llm=request.model`（分类用模块独立 LLM）
   - `request.messages` 是 `AnyMessage`，经 `adapt_messages` 转纯 dict 后才进分类器；`current_message` 取 `msgs[-1].text`

5. **build_system_prompt 改动**（只多一次占位替换，不 import 意图类型）：
   ```python
   def build_system_prompt(
       from_user: UserInfo,
       to_user: UserInfo,
       intent_context: str = "",
   ) -> str:
       # ... 现有 {{DH_USER_INFO}} / {{BH_USER_INFO}} 替换 ...
       return template.replace("{{INTENT_CONTEXT}}", intent_context)
   ```
   - `intent_context` 由 `intent_classifier.build_intent_context(intent)` 预渲染传入；默认 `""` 不破坏现有调用方（如 Chainlit `main.py` 不传该参即可）
   - `build_intent_context()` 定义在 `intent_classifier.py`（见上「代码实现」节），**不**放进 `prompts/utils.py`，避免 utils 依赖意图类型

## 延迟开启策略 (Gating)

### 动机

聊天最初的冷启动阶段（1-5 轮），消息高度同质化（"hey" / "wyd" / "what's up"），system prompt 中的 Cold Open 规则已经覆盖了回复策略。在这个阶段每次都调用 LLM 分类是浪费。

### 门控规则

**轮次门控**：前 5 个 BH 轮跳过 LLM 分类，直接走默认 system prompt。

"轮"的单位是 **BH 消息数**（`HumanMessage` 条数），由 `count_bh_turns(request.messages)` 计算，**不是** `request.messages` 的总长度（人+AI 都算会让阈值含义减半）。langchain 的 `ModelRequest` **没有 `message_count` 字段**，计数一律从 `request.messages` 派生。

注意：规则快速路径（`fast_path_classify`）**不受门控限制**——任何时候命中高危关键词都直接返回结果。门控只影响 LLM 分类路径。

```python
GATING_THRESHOLD = 5  # 前 5 个 BH 轮跳过；第 6 个 BH 轮起触发 LLM 分类


def should_classify(bh_turns: int) -> bool:
    """bh_turns = count_bh_turns(request.messages)（数 HumanMessage）。规则快路不受此限制。"""
    return bh_turns > GATING_THRESHOLD
```

### 效果估算

| 阶段 | 轮次 | 每会话消息数 | 规则命中（快路） | LLM 分类触发 | 节省 LLM 调用 |
|:---|:---|:---|:---|:---|:---|
| 冷启动 | 1–5 | ~5 | 仅高危词（~10% 概率） | 0 | ~4–5 次 |
| 升温 | 6–20 | ~15 | ~15-25% | 全部剩余 | 规则省 ~2-4 次 |
| 熟络 | 21+ | ~30 | ~15-25% | 全部剩余 | 规则省 ~5-8 次 |

按典型会话 30 轮计算，门控节省 ~5 次 LLM 调用，规则快路额外节省 ~7-12 次 LLM 调用，合计节省约 20-30% 的分类 LLM 调用。短会话节省比例更高。

### Intent Context 段（注入到 system prompt）

`system.md` **末尾**（Identity Foundation 之后）只追加一个 `{{INTENT_CONTEXT}}` 占位符。其内容由 `intent_classifier.build_intent_context()` 在代码里**整体生成**（不是 system.md 内的子模板替换），`None` 或 casual_chat + 无 flags 时注入空字符串，避免对大多数消息造成噪音。

放在最末尾是为 prompt cache：意图每条消息都变，是最易变内容；人设规则（静态）和线程内稳定的 DH/BH info 留在前缀，保证只有末尾这一小段 cache miss。

下面是 `build_intent_context()` 的渲染结果示例（label / confidence / flags 由代码按 `IntentResult` 填充）：

```markdown
## Intent Context

The user's CURRENT INTENT is: sexual (confidence: 0.85)
Additional signals: escalating, boundary_testing

Adjust your response strategy accordingly:
- `flirt` → You decide whether to reciprocate or play hard to get, based on the vibe so far.
- `sexual` early on → Shut it down. `sexual` after 30+ rounds of good vibe → You may tease back.
- `hostile` → React emotionally. Don't ignore. If severe, shut down.
- `bot_probe` → Deflect or mock. Never confirm.
- `low_effort` → Mirror their energy. Don't invest more than they do.
- `love_bombing` → Be skeptical. Slow them down.
- `personal_info` → **Hard boundary.** This means phone numbers, social media accounts (Instagram, Facebook, WeChat, WhatsApp, Snapchat), real name, workplace. Politely but firmly decline. Never provide. Example deflections: "i don't give that out here lol", "let's keep it on the app for now", "not ready for that yet". Do NOT tease or leave the door open.
- `meetup_request` → If vibe is good, tease without committing. If vibe is bad, hard no. Do NOT give your location or address.
- `escalating` → Be more guarded than usual.
- `boundary_testing` → Make it clear you noticed, without being dramatic.
- `deflecting` → Call it out lightly if it happens twice in a row.
```

## 评估体系

意图识别上线后需要持续评估质量。核心指标：

### Per-Class 指标

重点关注以下意图的准确率（误判代价高）：

| 意图 | 关注指标 | 原因 |
|:---|:---|:---|
| `sexual` | Recall | 漏判导致 DH 没有正确拒绝，用户体验差 |
| `hostile` | Recall | 漏判导致 DH 没有正确反应 |
| `bot_probe` | Precision | 误判导致 DH 莫名其妙 deflect，用户困惑 |
| `personal_info` | Recall | 漏判导致 DH 泄露信息风险 |
| `low_effort` | Precision | 误判导致 DH 冷落正常用户 |

### 评估流程

1. **种子数据集**：每类意图标注 30-50 条真实对话消息（含上下文），作为评估基准
2. **定期抽样**：每周从生产日志随机抽样 100 条，人工标注后计算 per-class F1
3. **Fallback Rate 监控**：跟踪 `fast_path_classify` 命中率、LLM 分类触发率、confidence < 0.6 降级率。如果 LLM 分类占比持续上升，说明规则覆盖不足，需要补充关键词

### 迭代机制

- 规则快路命中率 < 10% → 补充高频意图的关键词
- 某类意图 F1 < 0.7 → 检查 prompt 示例是否覆盖该类，考虑增加 few-shot
- confidence < 0.6 降级率 > 30% → prompt 可能需要调整（分类规则不够清晰，或示例与实际数据分布偏差大）

## 流式响应考量

当前 App 为非流式响应，分类延迟不影响首 token 感知。如果后续支持流式（SSE/gRPC stream）：

- **规则快路**：0ms 额外延迟，不影响流式体验
- **LLM 分类路径**：~100-200ms 额外延迟，用户会感知到这段时间内没有任何 token 输出
- **缓解方案**：如果流式场景下延迟成为问题，可将 LLM 分类替换为专用分类模型（DistilBERT 级，10-50ms），架构上只需替换 `_get_intent_llm()` 返回的模型（独立实例，不影响生成侧），接口不变

## 关键设计决策

1. **Pydantic + with_structured_output** 代替手写 JSON prompt。无需手写 Output Format 段，无需手写 JSON parsing 和错误处理，Schema 自动约束 LLM 输出，IDE 类型补全。

2. **上下文区分两个窗口**。LLM 分类看最近 5 条消息（控制 token 成本），代码层计算最近 20 条的统计特征（`CONVERSATION_STATS`：平均消息长度、重复短语等）作为 prompt hints 注入，弥补短窗口对 `low_effort` 等模式的判断盲区。

3. **confidence < 0.6 降级为 `casual_chat`**。不强行用低置信度标签驱动回复策略，避免误分类影响用户体验。

4. **规则快速路径 + LLM 双级分类**。高危强信号意图（sexual、hostile、bot_probe、personal_info 等）关键词特征明显，走规则快路 0ms 返回。剩余消息走 LLM 分类。规则快路命中预计 15-25%，省掉这部分 LLM 调用，也为后续替换为专用分类模型预留架构接口。

5. **分类使用独立 LLM 实例（与生成解耦）**。`intent_classifier.py` 自持 `intent_llm`（`deepseek-v4-flash`，关闭 thinking，**`temperature=0`** 保证分类确定性，避免生成侧 0.7 的抖动），与 `agent.py` 的生成 LLM 相互独立。通过 `with_structured_output` 走 tool calling 通道输出结构化结果，分类 prompt 极短延迟可控。后续降成本只需替换 `_get_intent_llm()` 返回的模型（或整体换专用分类模型），调用方接口不变。

6. **分类结果通过 middleware 注入**，与现有 `@dynamic_prompt` 模式一致，不改变 agent 核心结构。

7. **L2 辅助标签不互斥**，允许 `flirt` + `escalating` + `boundary_testing` 组合，给回复策略提供更细粒度的信号。初期由 LLM 输出，后续可根据准确度数据改为代码层比较连续两轮 L1 结果做后置规则判断。

8. **延迟开启 (Gating) 只影响 LLM 路径**。前 5 轮跳过 LLM 分类，规则快路任何时候都生效。冷启动阶段的 "hey" / "wyd" 不需要 LLM 分类。门控 + 规则快路合计节省约 20-30% 的 LLM 调用。

9. **Prompt Cache 优化**。分类 prompt 模板中，静态内容（Taxonomy、Rules、Examples）在前，动态变量（CONVERSATION_STATS、DH_PROFILE、BH_PROFILE、CHAT_HISTORY、CURRENT_MESSAGE）在末尾 `## Input` 段。每次请求仅末尾变量部分 cache miss，前缀全部命中。

10. **条件注入 Intent Context**。`casual_chat` + 无 flags 时不注入 Intent Context 段，减少 ~60-70% 消息的 token 消耗和噪音。仅在有意义的分类结果时才追加策略指引。

11. **松耦合模块边界**。`intent_classifier.py` 自包含（自持 `intent_llm`、schema、`FAST_PATH_RULES`、模板加载、`build_intent_context`），只 import `models.UserInfo`，**不 import `agent.py`**。分类器入参全是纯数据，不接收 langchain `ModelRequest`；`AnyMessage`→dict 适配（`adapt_messages`）和 BH 轮计数（`count_bh_turns`）放在 `agent.py` middleware 适配层——这是意图模块与现有代码的**唯一耦合点**。`build_system_prompt` 只收预渲染字符串 `intent_context: str`，`prompts/utils.py` 不依赖任何意图类型。好处：分类器可独立单测、可整体替换为 fastText/DistilBERT，对 agent 核心结构零侵入。
