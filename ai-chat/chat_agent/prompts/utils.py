from pathlib import Path

from core.models import UserInfo

_PROMPTS_DIR = Path(__file__).parent


def load_dating_summary_prompt() -> str:
    with open(_PROMPTS_DIR / "dating_summary_prompt.md", "r", encoding="utf-8") as f:
        return f.read()


def format_user_info(user: UserInfo) -> str:
    # 静态字段在前、动态字段在后；跳过未填写值（0 / 空串 / 空列表）。
    # current_time 每次请求都变，放最末尾以最大化 prompt cache 命中。
    lines = [f"Name: {user['nickname']}"]
    if user.get("age"):
        lines.append(f"Age: {user['age']}")
    lines.append(f"Gender: {user['gender']}")
    if user.get("height"):
        lines.append(f"Height: {user['height']}cm")
    city, state = user.get("city"), user.get("state_code")
    if city and state:
        lines.append(f"City: {city}, {state}")
    elif city:
        lines.append(f"City: {city}")
    elif state:
        lines.append(f"State: {state}")
    if user.get("location"):
        lines.append(f"Location: {user['location']}")
    if user.get("occupation"):
        lines.append(f"Occupation: {user['occupation']}")
    if user.get("education"):
        lines.append(f"Education: {user['education']}")
    if user.get("birthday"):
        lines.append(f"Birthday: {user['birthday']}")
    if user.get("race"):
        lines.append(f"Ethnicity: {user['race']}")
    interests = user.get("interests") or []
    if interests:
        tags = ", ".join(i["tag_key"] for i in interests)
        lines.append(f"Interests: {tags}")
    if user.get("bio"):
        lines.append(f"Bio: {user['bio']}")
    if user.get("current_time"):
        lines.append(f"Current time: {user['current_time']}")
    return "\n".join(lines)


def build_system_prompt(
    from_user: UserInfo,
    to_user: UserInfo,
    intent_context: str = "",
) -> str:
    """
    Args:
        from_user: DH（AI 扮演的人设）
        to_user: BH（真实用户，AI 对话的对象）
        intent_context: 预渲染的意图上下文字符串（由 intent_classifier.build_intent_context() 生成）。
                       空字符串时不注入内容，不破坏现有调用方。
    """
    with open(_PROMPTS_DIR / "system.md", "r", encoding="utf-8") as f:
        template = f.read()

    return template.replace(
        "{{DH_USER_INFO}}",
        format_user_info(from_user)
    ).replace(
        "{{BH_USER_INFO}}",
        format_user_info(to_user)
    ).replace(
        "{{INTENT_CONTEXT}}",
        intent_context,
    )
