"""ServiceResolver — 单个服务的地址缓存 + 订阅变更 + reconnect 用 refresh。"""

import asyncio
import logging
import threading
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.nacos_client.client import NacosClient

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
            logger.warning(
                "Failed to subscribe %s, will rely on refresh()", service_name,
                exc_info=True,
            )

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
