# 动态 System Prompt 方案

## 背景

当前架构中，`grpc_server.py` 的每个 `Chat` RPC 请求都调用 `create_agent_for_request`
新建 agent 实例（因为 system_prompt 随 BH/DH 用户对变化），仅 checkpointer 共享。

这导致：
- 每次请求重建 StateGraph 的开销
- prompt 构建逻辑与 agent 创建耦合在一起
- 无法实现所有会话共用的单一 agent 实例

## 方案

使用 LangChain 提供的 `@dynamic_prompt` middleware，结合 LangGraph 的
`Runtime.context` 机制，实现**单一 agent + 动态 system prompt**。

### 数据流

```
Service init
  └── build_agent(checkpointer)      ← 一次，全局共用一个 CompiledStateGraph

Chat RPC（每次请求）
  └── get bh_info, dh_info            ← 查询用户资料
  └── agent.ainvoke(
        config={thread_id},
        context=UserContext(bh, dh)   ← 注入 runtime context
    )
      └── LangGraph 框架自动将 context 放入 Runtime
      └── "model" 节点执行
            └── @dynamic_prompt 中间件拦截
            └── 读取 request.runtime.context
            └── 调用 build_system_prompt(from_user=dh, to_user=bh)
            └── 将生成的 SystemMessage 注入到 LLM 请求
```

### 核心类型

```python
@dataclass
class UserContext:
    bh_info: UserInfo   # BH = 真实用户，聊天对象
    dh_info: UserInfo   # DH = AI 人设，需要扮演的角色
```

### 关键 API

- `create_agent(..., context_schema=UserContext)` — 声明 context schema
- `agent.ainvoke(input, config, context=UserContext(...))` — 注入 context
- `@dynamic_prompt` — 装饰器，将函数注册为中间件，在每次 model 调用前执行
- `request.runtime.context` — 读取注入的 context

### 涉及文件

| 文件 | 改动 |
|------|------|
| `agent.py` | 新增 `UserContext`、`@dynamic_prompt` middleware、`build_agent()`，删除 `create_agent_for_request` |
| `chat_agent/grpc_server.py` | `__init__` 创建单一 agent，`Chat` RPC 注入 context |
| `main.py` | 适配新 API |

## 参考

- `langchain.agents.middleware.dynamic_prompt` — 文档见 `docs.langchain.com`
- `langgraph.runtime.Runtime.context` — LangGraph v0.6.0+
