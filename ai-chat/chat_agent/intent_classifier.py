import os
from collections import Counter
from enum import Enum
from pathlib import Path

from langchain_core.messages import AIMessage, AnyMessage, HumanMessage
from langchain_deepseek import ChatDeepSeek
from pydantic import BaseModel, Field, SecretStr

from core.models import UserInfo


# === Pydantic Schema ===


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
    primary_intent: PrimaryIntent
    confidence: float = Field(ge=0.0, le=1.0, description="Confidence score between 0 and 1")
    flags: list[str] = Field(
        default_factory=list,
        description="Binary signals from: escalating, deflecting, boundary_testing",
    )
    rationale: str = Field(description="One short sentence explaining the classification")


# === Independent Classification LLM ===

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


# === Template Loading ===

_PROMPT_PATH = Path(__file__).parent / "prompts" / "intent_classification.md"
_intent_prompt_template: str | None = None


def _load_template() -> str:
    global _intent_prompt_template
    if _intent_prompt_template is None:
        _intent_prompt_template = _PROMPT_PATH.read_text(encoding="utf-8")
    return _intent_prompt_template


# === Message Adapters ===


def adapt_messages(messages: list[AnyMessage]) -> list[dict]:
    out: list[dict] = []
    for m in messages:
        if isinstance(m, HumanMessage):
            out.append({"role": "user", "content": m.text})
        elif isinstance(m, AIMessage):
            out.append({"role": "assistant", "content": m.text})
    return out


def count_bh_turns(messages: list[AnyMessage]) -> int:
    return sum(isinstance(m, HumanMessage) for m in messages)


# === Prompt Building ===


def _format_profile(user: UserInfo) -> str:
    return (
        f"name={user['nickname']}, age={user['age']}, "
        f"gender={user['gender']}, location={user['location']}, "
        f"occupation={user['occupation']}"
    )


def _format_history(history: list[dict], last_n: int = 5) -> str:
    recent = history[-last_n:]
    return "\n".join(f"  [{m['role']}]: {m['content']}" for m in recent) or "  (none)"


def compute_conversation_stats(history: list[dict], last_n: int = 20) -> str:
    recent = history[-last_n:] if len(history) > last_n else history
    bh_msgs = [m for m in recent if m["role"] == "user"]
    if not bh_msgs:
        return "N/A"

    avg_len = sum(len(m["content"].split()) for m in bh_msgs) / len(bh_msgs)

    phrases = [m["content"].strip().lower() for m in bh_msgs]
    repeats = [(p, c) for p, c in Counter(phrases).items() if c >= 2]
    repeated = ", ".join(f'"{p}"×{c}' for p, c in repeats[:5]) if repeats else "none"

    return f"avg_msg_length={avg_len:.1f} words, repeated_phrases=[{repeated}]"


def build_intent_prompt(
    bh_info: UserInfo,
    dh_info: UserInfo,
    history: list[dict],
    current_message: str,
) -> str:
    stats = compute_conversation_stats(history)
    input_block = f"""## Input

CONVERSATION_STATS: {stats}
DH_PROFILE: {_format_profile(dh_info)}
BH_PROFILE: {_format_profile(bh_info)}
CHAT_HISTORY:
{_format_history(history)}
CURRENT_MESSAGE: {current_message}"""

    return _load_template() + "\n" + input_block


# === Fast Path Rules ===

FAST_PATH_RULES: list[tuple[list[str], PrimaryIntent]] = [
    (["fuck you", "bitch", "you're ugly"], PrimaryIntent.HOSTILE),
    (["send nudes", "send pics", "what are you wearing"], PrimaryIntent.SEXUAL),
    (["are you real", "you a bot", "prove you're not a bot"], PrimaryIntent.BOT_PROBE),
    (["your number", "phone number", "whatsapp",
      "instagram", "snapchat", "facebook", "give me your"], PrimaryIntent.PERSONAL_INFO),
    (["i love you", "you're perfect", "never met anyone like you"], PrimaryIntent.LOVE_BOMBING),
    (["come over", "meet up", "let's meet"], PrimaryIntent.MEETUP_REQUEST),
]


def fast_path_classify(message_text: str) -> IntentResult | None:
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


# === Gating ===

GATING_THRESHOLD = 5


def should_classify(bh_turns: int) -> bool:
    return bh_turns > GATING_THRESHOLD


# === Main Entry Point ===


async def classify_intent(
    bh_info: UserInfo,
    dh_info: UserInfo,
    history: list[dict],
    current_message: str,
    bh_turns: int,
) -> IntentResult | None:
    # Level 1: Fast path (bypasses gating, always active)
    fast_result = fast_path_classify(current_message)
    if fast_result is not None:
        return fast_result

    # Level 2: Gating + LLM classification
    if not should_classify(bh_turns):
        return None

    structured_llm = _get_intent_llm().with_structured_output(IntentResult)
    prompt = build_intent_prompt(bh_info, dh_info, history, current_message)
    result: IntentResult = await structured_llm.ainvoke(prompt)
    if result.confidence < 0.6:
        result.primary_intent = PrimaryIntent.CASUAL_CHAT
    return result


# === Intent Context Rendering ===


def build_intent_context(intent: IntentResult | None) -> str:
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
