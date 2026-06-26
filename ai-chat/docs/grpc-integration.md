# gRPC Integration Plan

## Context

将 chat-agent 改造为 gRPC 服务，对外提供真人扮演类 AI 聊天能力。

**名词解释:**
- 系统中真实用户：BioHuman简称BH
- 系统中的虚拟人：DigitalHuman简称DH
- 一般from_user_id为BH，to_user_id为DH
- DH通过AI生成针对BH的回复
- 项目中的thread_id使用BH和DH组成的字符串小的在前大的在后，比如BH:12345, DH:54321，thread_id = '12345_54321'

**业务模型：**
- `from_user_id` — BH，其 profile 通过调用内部用户服务获取
- `to_user_id` — DH，其 profile 通过调用内部用户服务获取
- AI 以 `to_user` 的身份，生成回复给 `from_user` 的消息

**约束：**
- Proto 来自组织共享nexus私服仓库，以 uv(pip) 包方式引入
- 只需 Chat（单轮） RPC
- Chainlit Web UI 保留，与 gRPC **独立进程**共存（`chainlit run main.py` 仅用于本地调试 prompt，不随 gRPC 一起部署）

## gRPC 请求/响应

```protobuf
service ChatAgent {
  rpc Chat(ChatRequest) returns (ChatResponse);
}

message ChatRequest {
  string thread_id = 1;     // 会话ID，复用 LangGraph checkpoint
  string from_user_id = 2;  // userID(真人userID)
  string to_user_id = 3;    // Digital Human(userID)
  string message = 4;       // 用户消息
}

message ChatResponse {
  string content = 1;       // AI 完整回复
}

```

## Architecture

```
                          ┌──────────────┐
                          │  grpc client │  (其他内部服务)
                          └──────┬───────┘
                                 │ gRPC
                          ┌──────▼────────┐         ┌─────────────────┐
                          │ grpc_server   │  gRPC   │  UserService    │
                          │ (grpc.aio)    │◄───────►│ (获取 BH/DH 资料) │
                          └──────┬────────┘         └─────────────────┘
                                 │
                          ┌──────▼────────┐
                          │ AgentCache    │  (按 persona_id 缓存 agent)
                          └──────┬────────┘
                                 │
                          ┌──────▼────────┐
                          │  agent.py     │
                          │  factory      │
                          └───────────────┘
```

**组件说明：**
- **grpc_server**：接收 `ChatAgent.Chat` RPC 请求，协调下游组件
- **UserService**：外部 gRPC 服务（`user/UserService`），同时查询 BH 和 DH 的用户资料。`GetUserInfoResponse` 当前返回字段：`user_id, nickname, avatar_url, gender(enum), bio`（暂无 age/city，proto 早期，后续会扩展）
- **AgentCache**：按 DH 的 persona_id 管理 agent 实例。初期方案 A 为每次请求新建 agent（简单可靠）；后续方案 B 为按 persona_id 缓存，通过 configurable 动态注入 BH 信息
- **agent.py**：LangGraph agent 工厂，组装 system prompt 并创建 agent

### 请求处理流程

```
ChatRequest (from_user_id=BH, to_user_id=DH, message)
       │
       ▼
  grpc_server.Chat()
       │
       ├──► UserService.GetUserInfo(from_user_id) ──► bh_info (BH 真人资料)
       │
       └──► UserService.GetUserInfo(to_user_id)   ──► dh_info (DH 虚拟人资料)
       │
       ▼
  build_system_prompt(dh_info, bh_info)
       │  {{DH_USER_INFO}} ← dh_info (AI 扮演的人设)
       │  {{BH_USER_INFO}} ← bh_info (AI 对话的对象)
       ▼
  agent.invoke({messages: [HumanMessage(message)]}, {thread_id: ...})
       │
       ▼
  ChatResponse(content=ai_message)
```

### System Prompt 组装

沿用现有 `prompts/system.md` 模板，填充两方信息：

```
{{DH_USER_INFO}}  ←  DH 虚拟人 — "You are this person"（AI 扮演的人设资料）
{{BH_USER_INFO}}  ←  BH 真人   — "You are speaking to this person"（AI 对话的对象资料）
```

注意 agent.py 中 `build_system_prompt(from_user, to_user)` 的参数命名：
- `from_user` = DH（说话的人，即 AI 人设）
- `to_user` = BH（接收消息的人，即真实用户）
这与 gRPC 接口 `ChatRequest` 中的 from/to 含义相反，后者以真实用户视角命名。

## Files to Create

| File | Purpose |
|------|---------|
| `chat_agent/__init__.py` | 包初始化 |
| `chat_agent/grpc_server.py` | gRPC 服务端：`ChatAgentServicer` + `start_server()` |

## Files to Modify

| File | Change |
|------|--------|
| `agent.py` | 1. 删除全局 `agent` 变量和 `MOCK_FROM_USER`、`MOCK_TO_USER`（移至 `main.py`）<br>2. `get_system_prompt` → `build_system_prompt`，去掉 None 默认值，要求显式传参<br>3. 新增 `create_agent_for_request(llm, system_prompt, checkpointer)` 工厂函数 |
| `main.py` | 1. `MOCK_DH`/`MOCK_BH` 定义移入此处（仅本地测试用）<br>2. 用 `build_system_prompt` + `create_agent_for_request` 创建模块级 `_agent`<br>3. `agent.invoke` → `await agent.ainvoke`（async 修正） |
| `pyproject.toml` | 添加 `grpcio`、`langgraph`、proto pip 包（`jianjiange-proto-chat`、`jianjiange-proto-user`）；添加 Nexus 索引 |

## AgentCache 设计

**关键问题：agent 缓存 vs 动态 BH_USER_INFO**

Agent 创建时 system_prompt 已固定，但 BH（真实用户）每个请求都不同。两个方案：

- **方案 A（初期推荐）**：不缓存 agent，每次请求动态组装 system_prompt 后创建 agent。优点：实现简单、正确性易保证。缺点：每次请求重建 StateGraph。
- **方案 B（后续优化）**：按 DH 的 persona_id 缓存 agent。`{{DH_USER_INFO}}` 在创建时填入，`{{BH_USER_INFO}}` 使用 LangGraph 的 `{configurable.xxx}` 占位符在请求时动态注入。

初期采用方案 A 快速落地，后期如果性能成为瓶颈再切换到方案 B。

## gRPC Server 核心实现

### 1. `chat_agent/grpc_server.py` — gRPC 服务端

```python
"""gRPC server for ChatAgent service."""

import asyncio
import logging
import grpc
from concurrent import futures

from langgraph.checkpoint.memory import InMemorySaver

from jianjiange_proto_chat import chat_pb2, chat_pb2_grpc
from jianjiange_proto_user import user_pb2, user_pb2_grpc

from agent import build_system_prompt, create_agent_for_request

logger = logging.getLogger(__name__)


class ChatAgentServicer(chat_pb2_grpc.ChatAgentServicer):
    """Handles ChatAgent gRPC requests."""

    def __init__(self, llm, user_stub: user_pb2_grpc.UserServiceStub):
        self._llm = llm
        self._user_stub = user_stub
        # 所有对话共享同一 checkpointer，通过 thread_id 隔离
        self._checkpointer = InMemorySaver()

    async def Chat(self, request: chat_pb2.ChatRequest, context) -> chat_pb2.ChatResponse:
        # 1. 查询 BH（真实用户）资料
        bh_resp = await self._user_stub.GetUserInfo(
            user_pb2.GetUserInfoRequest(user_id=int(request.from_user_id))
        )

        # 2. 查询 DH（虚拟人设）资料
        dh_resp = await self._user_stub.GetUserInfo(
            user_pb2.GetUserInfoRequest(user_id=int(request.to_user_id))
        )

        # 3. 组装 system prompt
        system_prompt = build_system_prompt(
            from_user=_user_info_to_dict(dh_resp),
            to_user=_user_info_to_dict(bh_resp),
        )

        # 4. 创建 agent 并调用
        agent = create_agent_for_request(
            llm=self._llm,
            system_prompt=system_prompt,
            checkpointer=self._checkpointer,
        )

        result = await agent.ainvoke(
            {"messages": [{"type": "human", "content": request.message}]},
            {"configurable": {"thread_id": request.thread_id}},
        )

        # 5. 提取 AI 回复
        ai_message = result["messages"][-1]
        content = (
            ai_message.content
            if isinstance(ai_message.content, str)
            else ai_message.content[0].get("text", "")
        )

        return chat_pb2.ChatResponse(content=content)


def _user_info_to_dict(user_resp) -> dict:
    """将 UserService 返回的 proto 对象转为 agent 需要的 dict 格式。"""
    return {
        "name": user_resp.nickname,
        "gender": {0: "Unknown", 1: "Male", 2: "Female"}.get(user_resp.gender, "Unknown"),
        "bio": user_resp.bio,
    }


async def start_server(
    llm,
    user_service_addr: str,
    listen_addr: str = "[::]:50051",
):
    """启动 gRPC server，阻塞运行。"""
    server = grpc.aio.server(futures.ThreadPoolExecutor(max_workers=10))

    # 连接 UserService
    user_channel = grpc.aio.insecure_channel(user_service_addr)
    user_stub = user_pb2_grpc.UserServiceStub(user_channel)

    # 注册 servicer
    servicer = ChatAgentServicer(llm=llm, user_stub=user_stub)
    chat_pb2_grpc.add_ChatAgentServicer_to_server(servicer, server)

    server.add_insecure_port(listen_addr)
    logger.info(f"ChatAgent gRPC server listening on {listen_addr}")
    await server.start()
    await server.wait_for_termination()


if __name__ == "__main__":
    from agent import llm

    logging.basicConfig(level=logging.INFO)
    asyncio.run(start_server(
        llm=llm,
        user_service_addr="dating-user-info-service:8080",
    ))
```

### 2. `agent.py` — 改造

现有 `agent.py` 已有 `get_system_prompt()` 和 `format_user_info()`，改造点如下：

1. **重命名** `get_system_prompt` → `build_system_prompt`，移除 `None` 默认值，要求显式传参
2. **删除**全局 `agent` 变量和 `MOCK_FROM_USER`、`MOCK_TO_USER`（移至 `main.py` 用于本地测试）
3. **保留** `llm` 实例、`build_system_prompt(from_user, to_user)`、`format_user_info(user)`、`UserInfo` TypedDict
4. **新增**工厂函数：

```python
def create_agent_for_request(llm, system_prompt: str, checkpointer):
    """按请求创建 agent 实例。"""
    return create_agent(
        model=llm,
        tools=[],
        system_prompt=system_prompt,
        checkpointer=checkpointer,
    )
```

`build_system_prompt(from_user, to_user)` 保持不变：
- `from_user`（DH）→ `{{DH_USER_INFO}}` — AI 扮演的人设
- `to_user`（BH）→ `{{BH_USER_INFO}}` — AI 对话的对象

> **注意**：`format_user_info()` 当前输出 name/age/gender/city/bio 五个字段，而 UserService 只返回 name/gender/bio。gRPC 路径中通过 `_user_info_to_dict` 只映射可用字段；`format_user_info` 中的 age/city 仅用于 Chainlit 本地测试的 MOCK 数据，待 user.proto 扩展后补全。

### 3. 关键设计决策

- **共享 checkpointer**：所有 agent 共享同一个 `InMemorySaver` 实例，通过 `thread_id` 隔离对话历史。重启后历史丢失（后续可换 `SqliteSaver`）。
- **每次请求新建 agent**：初期方案 A，系统 prompt 动态组装后创建 agent。StateGraph 构造开销可接受。
- **UserService 集成**：通过 gRPC async stub 调用 `user/UserService.GetUserInfo`，获取 BH 和 DH 的资料。UserService 地址通过参数传入。

## Verification

1. `uv run python -m chat_agent.grpc_server` — 启动 gRPC server
2. `grpcurl -plaintext -d '{"thread_id":"u1","from_user_id":"1","to_user_id":"2","message":"hey"}' localhost:50051 chat.ChatAgent/Chat` — 验证单轮对话
3. 同一 thread_id 多发消息，验证多轮上下文保持
4. 不同 to_user_id（DH）验证角色切换
5. `uv run chainlit run main.py` — 验证 Chainlit 仍正常工作
