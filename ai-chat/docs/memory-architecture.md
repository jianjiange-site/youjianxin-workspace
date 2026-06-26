# Memory Architecture：DH 长期记忆一致性方案

## 背景与目标

**核心需求**：DH 对 BH 说过"我喜欢苹果"，几个月后 BH 再问，DH 依然回答"苹果"——即 DH 对自身表达过的内容保持长期一致。

### 关键设计前提

`thread_id` 对同一 BH-DH 对**永久复用**，格式为 `{min(bh_id, dh_id)}_{max(bh_id, dh_id)}`。

这意味着：**PostgresSaver + 同一 thread_id = 全量历史永远可恢复 = DH 天然记得自己说过的一切。**

### 为什么不用 RAG / LangMem

| 方案 | 适用场景 | 此场景的问题 |
|---|---|---|
| RAG / pgvector | 海量知识库语义检索 | DH 一致性需要精确记忆，不是模糊匹配 |
| LangMem 自动提取 | 通用事实提取 | 提取有误差，可能造成 DH 自相矛盾 |
| **PostgresSaver（推荐）** | 线性对话历史 | ✅ 精确、可靠、无额外成本 |

### 真正需要解决的两个问题

1. **现在**：`InMemorySaver` 重启即丢 → **Phase 1：PostgresSaver**
2. **将来**：对话几千轮后全量传 LLM → token 成本爆炸 → **Phase 2：历史压缩（Summarization）**

---

## 架构总览

```
BH 发消息
    │
    ├─ 从 Postgres 加载该 thread_id 的完整对话历史
    │       （DH 说过的所有内容都在其中）
    │
    ├─ 历史消息数 > SUMMARY_THRESHOLD？
    │     YES ──→ 用摘要 + 最近 N 条替代全量历史（Phase 2）
    │     NO  ──→ 直接使用完整历史
    │
    └─ 发给 LLM → DH 回复（自动保持与历史一致）
```

**无需向量数据库，无需 Embedding API，零额外成本。**

---

### 当前代码现状

当前 `agent.py` 使用 `create_agent(llm, tools=[], system_prompt=..., checkpointer=...)` 创建 agent。
`create_agent` 是 LangChain 统一的高层 API，**内部已构建了一个 LangGraph**（含 model 节点、tools 节点、边）。

所以当前架构本质是：

```
create_agent()  ← LangChain 统一入口
  └── 内部 LangGraph（默认结构）
       ├── Node: "model"  → 调 LLM
       ├── Node: "tools"  → 执行工具（当前为空列表，永不触发）
       └── Edge: START → model → END（tools 空时退化为简单路径）
```

**Phase 2 本质**：当需要 Summarization 时，放弃 `create_agent` 的默认结构，
自己手写 `StateGraph` 加入 summarization 节点和条件边。

---

## 基础设施

复用团队现有 PG 实例，**docker-compose.yml 无需改动**：

```
PostgreSQL  38.76.188.242:5433  db=dating-chat
```

---

## Phase 1：PostgresSaver（立即实施）

### 新增依赖（pyproject.toml）

```toml
"langgraph-checkpoint-postgres>=2.0.0",
"psycopg[binary,pool]>=3.1.0",
```

### env.example 新增

```bash
PG_HOST=38.76.188.242
PG_PORT=5433
PG_DB=dating-chat
PG_USER=jianjian_test
PG_PASSWORD=your_password
```

### chat_agent/grpc_server.py 改动

**替换导入：**
```python
# 删除
from langgraph.checkpoint.memory import InMemorySaver
# 新增
from langgraph_checkpoint_postgres import AsyncPostgresSaver
```

**`start_server` 函数**：接收 `db_uri`，checkpointer 通过 async context manager 管理：
```python
async def start_server(llm, user_service_addr, listen_addr="[::]:50051", db_uri=None):
    async with AsyncPostgresSaver.from_conn_string(db_uri) as checkpointer:
        await checkpointer.setup()  # 幂等建表，重启安全

        server = grpc.aio.server(futures.ThreadPoolExecutor(max_workers=10))
        user_channel = grpc.aio.insecure_channel(user_service_addr)
        user_stub = user_pb2_grpc.UserServiceStub(user_channel)

        servicer = ChatAgentServicer(llm=llm, user_stub=user_stub, checkpointer=checkpointer)
        chat_pb2_grpc.add_ChatAgentServicer_to_server(servicer, server)
        server.add_insecure_port(listen_addr)
        await server.start()
        await server.wait_for_termination()
```

**`ChatAgentServicer.__init__`**：checkpointer 改为外部传入：
```python
def __init__(self, llm, user_stub, checkpointer):
    self._llm = llm
    self._user_stub = user_stub
    self._checkpointer = checkpointer
```

**`__main__` 块**：
```python
db_uri = (
    f"postgresql://{os.environ['PG_USER']}:{os.environ['PG_PASSWORD']}"
    f"@{os.environ.get('PG_HOST','38.76.188.242')}:{os.environ.get('PG_PORT','5433')}"
    f"/{os.environ.get('PG_DB','dating-chat')}"
)
asyncio.run(start_server(
    llm=llm,
    user_service_addr=os.environ.get("USER_SERVICE_ADDR", "dating-user-info-service:8080"),
    listen_addr=os.environ.get("GRPC_LISTEN_ADDR", "[::]:50051"),
    db_uri=db_uri,
))
```

**`main.py`（Chainlit 本地 demo）不改**，继续用 InMemorySaver。

---

## Phase 2：历史压缩[Summarization Node]（后续实施，按需启动）

### 问题

对话轮次增多后（数百轮），全量历史传 LLM，token 成本线性增长。

### 方案：Summarization（摘要压缩）

### 原理

不使用 RAG 和 Embedding，而是用 LLM 对旧历史生成**自然语言摘要**：
LangGraph Graph 中加入一个条件节点，消息数超阈值时自动触发压缩，将旧消息浓缩为摘要：

```
第 1~20 轮（消息数 < 阈值）
─────────────────────────────────────────────────────────────
  BH 消息 → START → 检查消息数 ≤ 20，跳过摘要 → LLM → END

第 21 轮（触发压缩）
─────────────────────────────────────────────────────────────
  BH 消息
     │
     ▼
  START ── 消息数 > 20 ──▶ summarize()
                                │
                      调用 LLM 生成摘要
                      "DH说过：喜欢苹果、不喜欢下雨天..."
                                │
                                ▼
                       State 更新：
                       { summary: "DH说过：...",
                         messages: [最近2条] }   ← 旧消息清空
                                │
                                ▼
                           call_llm()
                                │
                    注入 SystemMessage(摘要) + 最近 N 条
                                │
                                ▼
                            AI 回复 → END

第 22~40 轮（摘要滚动更新）
─────────────────────────────────────────────────────────────
  再次超阈值 → summarize()
  新摘要 = LLM(旧摘要 + 待压缩消息)   ← 滚动追加，非重建
```

### 关键点

- `summary` 字段与 `messages` 同存于 LangGraph State，通过 checkpointer **自动持久化到 Postgres，无需额外的表**
- 摘要是**滚动追加**（旧摘要 + 新消息 → 新摘要），不是每次从头重建
- 传给 LLM 的 token 数始终固定：`len(摘要) + N 条消息`，不随对话轮次线性增长
- 不需要向量数据库、不需要 Embedding，零额外成本
- LangGraph 内置 `trim_messages` 辅助截断历史                                                                                                                                                                    
- 摘要生成可以异步，不阻塞响应                                                                                                                                                                                   
- 摘要触发阈值通过环境变量 `SUMMARY_THRESHOLD`（默认 100）控制                                                                                                                                                   
- 摘要格式固定两段：「DH 说过的」和「BH 透露的」，注入 system prompt  

### 实现骨架

**说明**：Phase 2 需要**替换** `create_agent()` 为自定义 `StateGraph`。
核心思路是用手写 Graph 替代 `create_agent` 的默认结构，并在其中插入 summarization 节点。

```python
import os
from langgraph.graph import StateGraph, MessagesState, START, END
from langchain_core.messages import SystemMessage, HumanMessage, RemoveMessage

class State(MessagesState):
    summary: str  # 新增摘要字段，其余继承 messages

SUMMARY_THRESHOLD = int(os.environ.get("SUMMARY_THRESHOLD", "100"))

def should_summarize(state: State) -> str:
    return "summarize" if len(state["messages"]) > SUMMARY_THRESHOLD else "call_llm"

def summarize(state: State) -> dict:
    existing = state.get("summary", "")
    # 待压缩的旧消息（保留最近 2 条）
    messages_to_compress = state["messages"][:-2]
    prompt = f"已有摘要：{existing}\n\n请更新摘要，加入以下新对话内容..."
    new_summary = llm.invoke(messages_to_compress + [HumanMessage(prompt)])
    # MessagesState 的 messages 字段使用 add_messages reducer（追加语义）
    # 必须用 RemoveMessage 显式删除旧消息，不能直接返回截断后的列表
    delete_ops = [RemoveMessage(id=m.id) for m in messages_to_compress]
    return {
        "summary": new_summary.content,
        "messages": delete_ops,
    }

def call_llm(state: State) -> dict:
    messages = state["messages"]
    if summary := state.get("summary"):
        # 把摘要注入为 SystemMessage 前置
        messages = [SystemMessage(f"历史摘要：{summary}")] + messages
    return {"messages": [llm.invoke(messages)]}

# Graph 定义
builder = StateGraph(State)
builder.add_node("summarize", summarize)
builder.add_node("call_llm", call_llm)
builder.add_conditional_edges(START, should_summarize)
builder.add_edge("summarize", "call_llm")
builder.add_edge("call_llm", END)
graph = builder.compile(checkpointer=checkpointer)
```

### 新增环境变量

```bash
SUMMARY_THRESHOLD=100    # 超过此条数触发摘要压缩（Phase 2 生效）
```

### env.example 补充

Phase 1 已添加 `PG_*` 变量，Phase 2 需补充：

```bash
SUMMARY_THRESHOLD=100
```

---

## 实施顺序

**Phase 1（立即，1 天）**
1. `pyproject.toml` 加依赖，`uv sync`
2. `grpc_server.py` 替换 InMemorySaver → AsyncPostgresSaver
3. 服务器 `.env` 加 PG 连接变量
4. 部署，验证重启后历史保留

**Phase 2（按需，对话量大后再做）**
1. 监控 thread 消息量，确认是否到达成本瓶颈
2. 实现摘要生成 + 存储逻辑
3. 修改 Chat() 方法注入摘要

---

## 注意事项

| 事项 | 说明 |
|---|---|
| 表名冲突 | langgraph 默认建 `checkpoints`、`checkpoint_writes` 表，确认与现有表无冲突 |
| `setup()` 幂等 | 每次重启都可安全调用，不会覆盖已有数据 |
| Phase 2 独立 | Phase 2 可在 Phase 1 运行稳定后单独实施，互不影响 |
| Redis 暂不使用 | 现有 Redis 实例暂无用武之地，Phase 2 后如需缓存摘要可引入 |
