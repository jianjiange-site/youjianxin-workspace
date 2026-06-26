# Nacos 服务注册、发现与配置管理接入方案

## 背景

当前 ai-chat gRPC 服务以静态地址方式直连 user-service（`USER_SERVICE_ADDR` 环境变量），且自身未向任何注册中心注册，其他服务无法通过服务名发现它。同时，业务配置（如 LLM 参数、超时时间等）散落在环境变量中，无法动态管理。

**改造目标：**
1. **本服务注册到 Nacos**：启动时以 IP+50051 将 `ai-chat` 注册到 Nacos，关闭时注销
2. **user-service 改为 Nacos 发现**：通过 Nacos 解析地址并订阅变更，对端重新发布后无需重启 ai-chat
3. **配置管理接入 Nacos**：支持从 Nacos 读取配置项，可选监听配置变更实现热更新

**Nacos 配置：**

| 参数 | 值 |
|---|---|
| namespace | `dating-test` |
| group | `DEFAULT_GROUP` |
| user-service 服务名 | `user-service` |
| 本服务注册名 | `ai-chat` |
| SDK 版本 | `nacos-sdk-python>=2.0.0,<3.0.0`（Nacos 服务端 2.5.1，3.x SDK 仅支持 Nacos 3.x） |

---

## 改造文件清单

| 文件 | 变更类型 |
|---|---|
| `pyproject.toml` | 新增依赖 `nacos-sdk-python` |
| `nacos_client/__init__.py` | **新建**：公开 API，init/get/reset 生命周期 + 类型导出 |
| `nacos_client/client.py` | **新建**：NacosClient — 注册/注销/服务解析/配置读取 |
| `nacos_client/resolver.py` | **新建**：ServiceResolver — 单个服务的地址缓存 + 订阅变更 + reconnect 用 refresh |
| `chat_agent/clients/user_client.py` | `__init__` 新增可选 `resolver: ServiceResolver`；出错时 `_reconnect` |
| `chat_agent/grpc_server.py` | 启动时 init NacosClient → 创建 ServiceResolver → 传入 UserServiceClient |
| `env.example` | 新增 Nacos 相关环境变量 |

---

## 架构设计

### 核心抽象：ServiceResolver

把服务发现的复杂性封装在一个对象里，业务 Client 只依赖它，不 import 任何 Nacos 代码。

```
                    ┌──────────────────────────────────┐
                    │         ServiceResolver           │
                    │                                  │
   grpc_server.py   │  current() → 读缓存（零开销）      │────► UserServiceClient
   ──► resolver ────│  refresh() → run_in_executor     │        _reconnect()
                    │  subscribe → SDK 线程回调更新缓存  │
                    │                                  │
                    └──────────┬───────────────────────┘
                               │
                         NacosClient
```

### 三层容错

对端服务重新发布导致 IP 变化时，不需要重启 ai-chat：

```
RPC 调用失败
  │
  ├─ 1. gRPC 内置重连 ───────► 成功（对端暂时不可达，同一地址恢复）
  │
  └─ 2. _reconnect()
        │
        ├─ resolver.current()  ← 缓存大概率已最新
        │   （subscribe 在后台静默更新过）
        │
        └─ resolver.refresh()  ← 最后保险
            run_in_executor 查 Nacos，不阻塞 event loop
```

---

## 实施细节

### 1. `pyproject.toml` — 新增依赖

```toml
"nacos-sdk-python>=2.0.0,<3.0.0",
```

### 2. `nacos_client/__init__.py` — 生命周期管理

```python
import threading
from nacos_client.client import NacosClient, NacosConfig
from nacos_client.resolver import ServiceResolver

__all__ = ["NacosClient", "NacosConfig", "ServiceResolver", "init", "get", "reset"]

_nacos: NacosClient | None = None
_lock = threading.Lock()


def init(config: NacosConfig) -> NacosClient:
    """初始化全局 NacosClient。重复调用抛异常。"""
    global _nacos
    with _lock:
        if _nacos is not None:
            raise RuntimeError("NacosClient already initialized")
        _nacos = NacosClient(config)
    return _nacos


def get() -> NacosClient:
    """获取已初始化的 NacosClient。未 init 抛异常。"""
    with _lock:
        if _nacos is None:
            raise RuntimeError("NacosClient not initialized, call nacos_client.init() first")
    return _nacos


def reset() -> None:
    """仅测试用：重置全局状态。"""
    global _nacos
    with _lock:
        _nacos = None
```

### 3. `nacos_client/client.py` — NacosClient 实现

```python
import json
import logging
import os
import random
import socket
from typing import Callable

import nacos

logger = logging.getLogger(__name__)


class NacosConfig:
    server_addr: str
    namespace: str = "dating-test"
    group: str = "DEFAULT_GROUP"
    service_name: str = "ai-chat"
    service_port: int = 50051
    username: str = "nacos"
    password: str = "nacos"

    def __init__(self, server_addr: str, **kwargs):
        self.server_addr = server_addr
        for k, v in kwargs.items():
            if hasattr(self, k):
                setattr(self, k, v)


class NacosClient:
    def __init__(self, config: NacosConfig, service_ip: str | None = None):
        self._client = nacos.NacosClient(
            config.server_addr,
            namespace=config.namespace,
            username=config.username,
            password=config.password,
        )
        self._group = config.group
        self._service_name = config.service_name
        self._ip = service_ip or _get_local_ip()
        self._port = config.service_port

    # ── 服务注册 ────────────────────────────────────────

    def register(self):
        self._client.add_naming_instance(
            self._service_name, self._ip, self._port,
            group_name=self._group, ephemeral=True,
        )

    def deregister(self):
        self._client.remove_naming_instance(
            self._service_name, self._ip, self._port, group_name=self._group,
        )

    # ── 服务发现 ────────────────────────────────────────

    def resolve(self, service_name: str) -> str:
        """从 Nacos 查询一个健康实例，返回 gRPC 'host:port'。

        注意：Java 服务（net.devh grpc-spring-boot-starter）注册到 Nacos 的
        实例端口是 web 端口（如 user-service 的 8080），gRPC 端口（9090）写在
        instance metadata 'gRPC.port' 里（net.devh 常量，部分版本兼容 'gRPC_port'）。
        这里必须取 metadata 端口，否则会连到 HTTP 端口导致 gRPC 调用全部失败。
        """
        result = self._client.list_naming_instance(
            service_name, group_name=self._group, healthy_only=True,
        )
        hosts = result.get("hosts", [])
        if not hosts:
            raise RuntimeError(f"No healthy instances for {service_name}")
        inst = random.choice(hosts)
        md = inst.get("metadata") or {}
        port = md.get("gRPC.port") or md.get("gRPC_port") or inst["port"]
        return f"{inst['ip']}:{port}"

    def subscribe(self, service_name: str, on_change: Callable[[], None]):
        """订阅服务实例变更。

        SDK 每 7s HTTP 轮询；listener 在 SDK 自己的 timer 线程以
        listener_fn(event, slc) 调用（event ∈ Event.ADDED/MODIFIED/DELETED，
        slc 是 SubscribedLocalInstance，slc.instance 才是 {ip,port,...} dict）。
        这里适配成无参 on_change() 通知，由调用方自行重算地址，不依赖单实例事件细节。
        """
        def _listener(event, slc):  # SDK timer 线程
            try:
                on_change()
            except Exception:
                logger.exception("subscribe on_change failed for %s", service_name)
        self._client.subscribe(
            listener_fn=_listener,
            service_name=service_name,
            group_name=self._group,
        )

    def unsubscribe(self, service_name: str):
        # 仅移除 listener；轮询 timer 需 stop_subscribe() 单独停止
        self._client.unsubscribe(service_name)

    def stop_subscribe(self):
        """停止 subscribe 轮询线程（shutdown 时调用一次）。"""
        self._client.stop_subscribe()

    # ── 配置管理 ────────────────────────────────────────

    def get_config(self, data_id: str, timeout: float = 10.0) -> str | None:
        return self._client.get_config(
            data_id=data_id, group=self._group, timeout=timeout,
        )

    def get_config_json(self, data_id: str, timeout: float = 10.0) -> dict:
        raw = self.get_config(data_id, timeout=timeout)
        if raw is None:
            logger.warning("Nacos config %s not found", data_id)
            return {}
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            logger.exception("Failed to parse Nacos config %s as JSON", data_id)
            return {}

    def watch_config(
        self, data_id: str, callback: Callable[[dict], None]
    ):
        def _on_change(cfg: dict):
            try:
                content = cfg.get("content", "{}")
                parsed = json.loads(content) if content else {}
                callback(parsed)
            except Exception:
                logger.exception("Error in Nacos config watcher for %s", data_id)

        self._client.add_config_watchers(
            data_id=data_id, group=self._group, cb_list=[_on_change],
        )


def _get_local_ip() -> str:
    # 优先显式指定（Docker 多网络时避免选错网卡），其次 K8s POD_IP，最后路由探测
    explicit = os.environ.get("NACOS_SERVICE_IP") or os.environ.get("POD_IP")
    if explicit:
        return explicit
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    finally:
        s.close()
```

### 4. `nacos_client/resolver.py` — ServiceResolver

```python
import asyncio
import logging
import threading
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from nacos_client.client import NacosClient

logger = logging.getLogger(__name__)


class ServiceResolver:
    """绑定到一个 Nacos 服务名，管理地址缓存和主动刷新。

    热路径（_reconnect）读取 .current() 是零开销的线程安全读；
    只有 .current() 失效时才调用 await .refresh()，走 run_in_executor。
    """

    def __init__(self, nacos: "NacosClient", service_name: str):
        self._nacos = nacos
        self._service = service_name
        self._lock = threading.Lock()
        self._addr = nacos.resolve(service_name)  # 启动时同步 resolve

        # 订阅 Nacos 实例变更（SDK 每 7s 轮询），变更时后台重算缓存
        try:
            nacos.subscribe(service_name, self._on_change)
        except Exception:
            logger.warning("Failed to subscribe %s, will rely on refresh()", service_name)

    def current(self) -> str:
        """返回缓存的 'host:port'。热路径安全，不调 Nacos。"""
        with self._lock:
            return self._addr

    async def refresh(self) -> str:
        """主动重新 resolve，内部走 run_in_executor 不阻塞 event loop。"""
        loop = asyncio.get_running_loop()
        addr = await loop.run_in_executor(None, self._nacos.resolve, self._service)
        with self._lock:
            self._addr = addr
        logger.info("ServiceResolver refreshed %s -> %s", self._service, addr)
        return addr

    def close(self):
        """取消订阅。"""
        try:
            self._nacos.unsubscribe(self._service)
        except Exception:
            pass

    # ── 内部回调（SDK 线程） ─────────────────────────────

    def _on_change(self):
        """subscribe 通知回调，在 SDK timer 线程执行。

        SDK listener 拿到的是单实例 + 事件，这里不逐个处理，直接重新 resolve
        （healthy_only=True，并取 metadata gRPC 端口）重算缓存，与 current()/
        refresh() 取值一致。在 SDK 自己的线程里同步阻塞 HTTP 安全，不碰 event loop。
        """
        try:
            addr = self._nacos.resolve(self._service)
        except Exception:
            logger.warning("ServiceResolver re-resolve %s failed", self._service)
            return
        with self._lock:
            if addr != self._addr:
                self._addr = addr
                logger.info("ServiceResolver updated %s -> %s", self._service, addr)
```

### 5. `chat_agent/clients/user_client.py` — 接入 ServiceResolver

改动极小，`__init__` 新增一个可选参数：

```python
from nacos_client.resolver import ServiceResolver

# 仅连接类错误才重连重试；业务错误（NOT_FOUND/INVALID_ARGUMENT 等）直接抛
_RETRYABLE = {grpc.StatusCode.UNAVAILABLE, grpc.StatusCode.DEADLINE_EXCEEDED}


class UserServiceClient:
    def __init__(
        self,
        addr: str,
        timeout: float = 5.0,
        resolver: ServiceResolver | None = None,
    ):
        self._addr = addr
        self._timeout = timeout
        self._resolver = resolver
        self._connect(addr)

    def _connect(self, addr: str):
        self._addr = addr
        self._channel = grpc.aio.insecure_channel(addr)
        self._stub = user_pb2_grpc.UserServiceStub(self._channel)

    async def _reconnect(self):
        """出错时重连：优先读 subscribe 缓存，必要时主动 refresh。"""
        await self._channel.close()
        if self._resolver:
            addr = self._resolver.current()
            if addr == self._addr:
                # 缓存没变，主动查一次 Nacos
                addr = await self._resolver.refresh()
        else:
            addr = self._addr
        self._connect(addr)

    async def get_user_info(self, user_id: int) -> UserInfo:
        try:
            resp = await self._stub.GetUserInfo(
                user_pb2.GetUserInfoRequest(user_id=user_id),
                timeout=self._timeout,
            )
        except grpc.aio.AioRpcError as e:
            if e.code() not in _RETRYABLE:
                raise UserServiceError(
                    f"GetUserInfo({user_id}) failed: {e.code().name}"
                ) from e
            await self._reconnect()
            try:  # reconnect 后仅重试一次
                resp = await self._stub.GetUserInfo(
                    user_pb2.GetUserInfoRequest(user_id=user_id),
                    timeout=self._timeout,
                )
            except grpc.aio.AioRpcError as e2:
                raise UserServiceError(
                    f"GetUserInfo({user_id}) failed after reconnect: {e2.code().name}"
                ) from e2
        return _to_user_info(resp)
```

### 6. `chat_agent/grpc_server.py` — 接入 Nacos

```python
import nacos_client
from nacos_client import NacosConfig, ServiceResolver


async def start_server(
    ...,
    nacos_config: NacosConfig | None = None,
    user_service_addr: str = "",
):
    # 初始化 Nacos（同步阶段已完成，这里只取引用）
    nacos = nacos_client.get() if nacos_config else None

    # 为每个依赖的服务创建一个 ServiceResolver
    resolver = None
    if nacos:
        resolver = ServiceResolver(nacos, "user-service")
        resolved_addr = resolver.current()
    else:
        resolved_addr = user_service_addr

    user_client = UserServiceClient(
        addr=resolved_addr,
        resolver=resolver,
    )

    await server.start()
    if nacos:
        nacos.register()

    await stop_event.wait()

    if nacos:
        nacos.deregister()
        resolver.close()
        nacos.stop_subscribe()  # 停止 subscribe 轮询线程
    await server.stop(shutdown_grace)
```

`__main__` 块：

```python
import re
import nacos_client
from nacos_client import NacosConfig

if __name__ == "__main__":
    nacos_server_addr = os.environ.get("NACOS_SERVER_ADDR")
    if nacos_server_addr:
        listen_addr = os.environ.get("GRPC_LISTEN_ADDR", "[::]:50051")
        port_match = re.search(r":(\d+)$", listen_addr)
        nacos_service_port = int(port_match.group(1)) if port_match else 50051
        nacos_config = NacosConfig(
            server_addr=nacos_server_addr,
            namespace=os.environ.get("NACOS_NAMESPACE", "dating-test"),
            group=os.environ.get("NACOS_GROUP", "DEFAULT_GROUP"),
            service_name=os.environ.get("NACOS_SERVICE_NAME", "ai-chat"),
            service_port=nacos_service_port,
            username=os.environ.get("NACOS_USERNAME", "nacos"),
            password=os.environ.get("NACOS_PASSWORD", "nacos"),
        )
        nacos_client.init(nacos_config)  # 必须在 asyncio.run 之前

    asyncio.run(start_server(
        ...,
        nacos_config=nacos_config,
        user_service_addr=os.environ.get("USER_SERVICE_ADDR", ""),
    ))
```

### 7. `env.example` — 新增 Nacos 变量

```
# Nacos 服务注册与发现（Nacos 服务端版本 2.5.1）
# NACOS_SERVER_ADDR 支持逗号分隔多个地址以实现高可用
NACOS_SERVER_ADDR=nacos:8848
NACOS_NAMESPACE=dating-test
NACOS_GROUP=DEFAULT_GROUP
NACOS_SERVICE_NAME=ai-chat
NACOS_USERNAME=nacos
NACOS_PASSWORD=nacos
# 可选：显式指定注册到 Nacos 的本服务 IP（Docker 多网络时避免自动探测选错网卡）
# NACOS_SERVICE_IP=
```

`USER_SERVICE_ADDR` 保留作 fallback（不配置 Nacos 时仍可直连）。

### 8. 配置管理（可选）

启动时加载 Nacos 配置：

```python
if nacos:
    llm_config = nacos.get_config_json("ai-chat-llm")
    if llm_config:
        apply_llm_config(llm, llm_config)
```

热更新：

```python
nacos.watch_config("ai-chat-llm", _on_llm_config_changed)
```

> `watch_config` 回调在 SDK 的 ThreadPool worker 线程执行（非 event loop）。若热更新要改 event loop 用到的状态，启动时存 `loop = asyncio.get_running_loop()`，回调内用 `loop.call_soon_threadsafe(...)` 切回主 loop 再 apply。回调 dict 的内容键是 `content`。

配置 data_id 命名约定：`{service-name}-{模块}`（如 `ai-chat-llm`、`ai-chat-grpc`）。

---

## 关键设计决策

| 决策 | 说明 |
|---|---|
| `ServiceResolver` 抽象 | 把"发现"从 Client 剥离为独立对象。Client 不 import Nacos，只依赖 `resolver: ServiceResolver \| None` |
| `current()` 零开销读 | 热路径读缓存，加轻量 `threading.Lock`，不调 Nacos、不阻塞 event loop |
| gRPC 端口取 metadata | 对端 Java 服务（net.devh）注册的实例端口是 web 端口（user-service 8080），gRPC 端口（9090）在 instance metadata `gRPC.port`；`resolve()` 必须取 metadata 端口，否则连到 HTTP 端口、gRPC 全失败 |
| subscribe（7s 轮询） | SDK 每 7s 轮询 Nacos；listener 签名是 `(event, slc)`，适配为无参 `on_change()` → 重新 `resolve()` 更新缓存；轮询线程在 shutdown 时由 `stop_subscribe()` 停止 |
| `refresh()` 最后保险 | 缓存未变时才主动 `run_in_executor` 查 Nacos，不阻塞 event loop |
| `nacos_client` 顶层独立包 | 与业务零依赖，其他 Python 服务可直接复用它接入 Nacos |
| `init()` 在 `asyncio.run` 之前 | NacosClient 创建是同步操作，确保 event loop 启动前配置就绪 |
| `threading.Lock` 保护全局状态 | `init/get/reset` 及 `ServiceResolver` 缓存读写均加锁，SDK 线程回调安全 |
| `NacosConfig` 聚合参数 | 所有 Nacos 参数集中为单一对象，`start_server()` 签名不受 Nacos 参数数量影响 |
| `ephemeral=True` | 临时实例，SDK 内置心跳，Nacos 心跳超时后自动摘除 |
| Retry 一次（仅连接错误） | `get_user_info` 仅在 `UNAVAILABLE`/`DEADLINE_EXCEEDED` 时 reconnect 后重试一次；业务错误（如 `NOT_FOUND`）直接抛 `UserServiceError`，不重连 |
| 兼容无 Nacos 模式 | `NACOS_SERVER_ADDR` 不填时走原有静态地址逻辑，本地 Chainlit 调试不受影响 |
| SDK 版本锁定 2.x | `nacos-sdk-python>=2.0.0,<3.0.0`，3.x 仅支持 Nacos 3.x |
| 配置不存在不阻塞启动 | `get_config_json` 返回 `{}`，服务使用代码默认值继续启动 |

---

## 目录结构

```
nacos_client/
├── __init__.py      # 公开 API + init/get/reset 生命周期
├── client.py        # NacosClient + NacosConfig + _get_local_ip
└── resolver.py      # ServiceResolver

chat_agent/
├── clients/
│   └── user_client.py   # UserServiceClient（新增 resolver 可选参数）
├── grpc_server.py       # 启动时 init Nacos → 创建 ServiceResolver → 传入 Client
├── agent.py             # 不变
└── ...
```

---

## 验证步骤

### 服务注册与发现

1. 配置 `.env` 加入 Nacos 变量，启动 gRPC server
2. 在 Nacos 控制台（服务列表 → group: `DEFAULT_GROUP`）确认 `ai-chat` 出现
3. 用 grpcurl 调用 `Chat` RPC，确认正常返回
4. **对端变更测试**：重启 user-service，确认 ai-chat 无需重启仍能正常调用（subscribe 更新缓存 → `_reconnect` 拿到新地址）
5. 停止 ai-chat，确认 Nacos 中实例注销
6. 移除 `NACOS_SERVER_ADDR` 变量，确认回退到静态地址模式

### 配置管理

7. 在 Nacos 控制台创建 `ai-chat-llm` 配置（group: `DEFAULT_GROUP`），写入 LLM 参数 JSON
8. 启动 server，确认日志打印了加载到的配置
9. 修改配置内容，确认 watcher 回调触发

### 新增一个依赖服务

10. 在 `grpc_server.py` 中新增一行 `ServiceResolver(nacos, "new-service")`，传入对应 Client 即可
