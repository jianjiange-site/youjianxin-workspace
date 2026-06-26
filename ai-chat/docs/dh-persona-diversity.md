# DH 回复去同质化 — 分析与方案

## 问题

> 所有 DH 回复高度同质化，一个 BH 收到的不同 DH 回复感觉像同一个人。

## 根因分析

### 1. 数据丢失

`UserProfile` proto 有 15+ 个字段，但 `_to_user_info()` 只映射了 5 个：

| Proto 字段 | 已映射 | 差异化潜力 |
|---|---|---|
| nickname, age, gender, location, bio | ✅ | 中 |
| occupation | ❌ | **高** — 职业强烈影响词汇和话题 |
| education | ❌ | 中 — 影响表达复杂度 |
| interests (repeated) | ❌ | **高** — 兴趣是对话的核心支柱 |
| height, birthday, avatar | ❌ | 低 |

### 2. 信号稀释

`system.md` 约 170 行，DH 信息块只有 5 行（`{{DH_USER_INFO}}`），且放在 prompt 末尾。LLM 对 prompt 首尾注意力权重最高，但 170 行的通用规则完全淹没了 DH 身份信号。

### 3. 无差异化机制

- 所有 DH 共享同一个 LLM 实例（固定 temperature=0.7）
- 所有 DH 共享同一套 system prompt 模板
- 没有 per-DH 的 persona/style/参数配置
- 没有 per-DH 的 prompt 注入或覆盖机制

## 方案

### 概述

三管齐下：

```
数据充实（利用已有 proto 字段）
  → LLM 生成角色卡（核心差异化引擎，缓存复用）
    → Prompt 重构（角色卡置顶，通用规则压缩为 guardrails）
```

### 详细步骤

#### Step 1: models.py — 扩展 UserInfo

```python
from typing import TypedDict, NotRequired

class UserInfo(TypedDict):
    name: str
    age: int
    gender: str
    city: str
    bio: str
    occupation: NotRequired[str]
    education: NotRequired[str]
    interests: NotRequired[list[str]]
```

`NotRequired` 保证向后兼容——Chainlit mock 和测试不加新字段也不会报错。

#### Step 2: user_client.py — 停止丢弃字段

```python
def _to_user_info(profile) -> UserInfo:
    info: UserInfo = {
        "name": profile.nickname,
        "age": profile.age,
        "gender": _GENDER_MAP.get(profile.gender, "Unknown"),
        "city": profile.location,
        "bio": profile.bio,
    }
    if profile.occupation:
        info["occupation"] = profile.occupation
    if profile.education:
        info["education"] = profile.education
    if profile.interests:
        info["interests"] = [i.tag_key for i in profile.interests]
    return info
```

#### Step 3: prompts/utils.py — 渲染新字段 + 接受角色卡

```python
def format_user_info(user: UserInfo) -> str:
    lines = [
        f"Name: {user['name']}",
        f"Age: {user['age']}",
        f"Gender: {user['gender']}",
        f"Location: {user['city']}",
        f"Bio: {user['bio']}",
    ]
    if user.get("occupation"):
        lines.append(f"Occupation: {user['occupation']}")
    if user.get("education"):
        lines.append(f"Education: {user['education']}")
    if user.get("interests"):
        lines.append(f"Interests: {', '.join(user['interests'])}")
    return "\n".join(lines)

def build_system_prompt(
    from_user: UserInfo,
    to_user: UserInfo,
    persona_card: str = "",
    intent_context: str = "",
) -> str:
    # 替换 {{DH_PERSONA_CARD}}、{{DH_USER_INFO}}、{{BH_USER_INFO}}、{{INTENT_CONTEXT}}
```

#### Step 4: 新文件 prompts/persona_generation.md

角色卡生成 prompt。输入 DH 的 profile 文本，输出约 200-300 字的角色卡，包含五段：
- **Voice** — 语气、消息长度、标点习惯、emoji 频率
- **Vocabulary** — 口头禅、俚语、禁用词、是否说脏话
- **Emotional Style** — 冷热、信任速度、冲突处理
- **Topics They Gravitate To** — 喜欢聊什么
- **Topics They Avoid or Deflect** — 回避什么

用英文 + second-person 写（"You talk in short bursts..."）。

#### Step 5: 新文件 chat_agent/persona.py — 角色卡缓存

```python
# 内存缓存: dh_id → persona_card
# 缓存失效: profile 关键字段 MD5 对比
# 降级: LLM 调用失败返回空字符串，不阻塞主流程

async def get_or_generate_persona(dh_id, info, llm) -> str
```

#### Step 6: prompts/system.md — 重构为 persona-first

结构变化：

```
旧结构（~170 行）：
  Role → Conversation Rules → Tone → Language → Situations → Fragments → Don'ts → Identity (末尾)

新结构（~70 行）：
  Role → Who You Are ({DH_PERSONA_CARD} + {DH_USER_INFO} + {BH_USER_INFO})
       → Conversation Rules（压缩到 ~30 行，阶段 + 语气表 + 底线）
       → Hard Boundaries（8 行）
       → {INTENT_CONTEXT}
```

关键变化：
- **角色卡放在 prompt 顶部**（Role 之后第一段），LLM 注意力权重最高
- 删除 `Chat Fragments`、`Language & Style` 示例、`Handling Specific Situations` 详细展开——角色卡接管风格表达
- 行为规则压缩为 guardrails，不再定义 personality

#### Step 7: agent.py — 集成

- `UserContext` 新增 `dh_id: int`
- 新增 `persona_llm`（temperature=0.5，角色卡生成需要稳定）
- `user_persona_prompt` 中间件内调用 `get_or_generate_persona()` 获取角色卡

#### Step 8: grpc_server.py — 传入 dh_id

```python
context=UserContext(bh_info=bh_info, dh_info=dh_info, dh_id=to_id)
```

#### Step 9: main.py — 更新 mock 数据

_MOCK_DH 补全 occupation/education/interests，加 `dh_id=0`。

### 延迟影响

| 场景 | 增量 |
|---|---|
| DH 首次消息（冷缓存） | +~500ms~1s（一次 persona LLM 调用） |
| 同 DH 后续消息 | ~0ms（内存缓存命中） |
| Prompt token 增量 | ~300 tokens（角色卡文本） |

### 依赖与风险

- **无需改 proto 或 user-service**——occupation/education/interests 已在现有 proto 中
- **向后兼容**——NotRequired 字段不影响已有调用方
- **降级安全**——persona LLM 调用失败返回空字符串，主流程不中断
- **缓存内存**——10K DH 约 20MB，可忽略

## 备选简化方案

如果不想引入 LLM 生成角色卡的复杂度，可以先只做 **Step 1-3 + Step 6（精简版）**：

1. 充实 UserInfo 数据
2. 把 DH 信息块从 prompt 末尾移到 Role 之后
3. 压缩通用规则到 ~50 行

这不需要新的 LLM 调用，零延迟影响，但差异化效果有限——DH 之间仍共享同一套行为规则，只是 profile 信息更显眼。
