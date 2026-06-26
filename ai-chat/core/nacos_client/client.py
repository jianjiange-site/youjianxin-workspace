"""NacosClient — 注册/注销/服务解析/配置读取。"""

import json
import logging
import os
import random
import socket
from typing import Callable

import nacos
from nacos.listener import SubscribeListener

logger = logging.getLogger(__name__)


class NacosConfig:
    server_addr: str
    namespace: str = "youjianxin-dating-dev"
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
            self._service_name,
            self._ip,
            self._port,
            group_name=self._group,
            ephemeral=True,
            heartbeat_interval=5,
        )

    def deregister(self):
        self._client.remove_naming_instance(
            self._service_name,
            self._ip,
            self._port,
            group_name=self._group,
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
            service_name,
            group_name=self._group,
            healthy_only=True,
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

        listener = SubscribeListener(
            fn=_listener,
            listener_name=f"{service_name}-listener",
        )
        self._client.subscribe(
            listener_fn=listener,
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
            data_id=data_id,
            group=self._group,
            timeout=timeout,
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

    def watch_config(self, data_id: str, callback: Callable[[dict], None]):
        def _on_change(cfg: dict):
            try:
                content = cfg.get("content", "{}")
                parsed = json.loads(content) if content else {}
                callback(parsed)
            except Exception:
                logger.exception("Error in Nacos config watcher for %s", data_id)

        self._client.add_config_watchers(
            data_id=data_id,
            group=self._group,
            cb_list=[_on_change],
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
