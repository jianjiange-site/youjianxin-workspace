"""Nacos 接入：服务注册/发现/配置管理。

顶层独立包，与业务零依赖，其他 Python 服务可直接复用接入 Nacos。
"""

import threading

from core.nacos_client.client import NacosClient, NacosConfig
from core.nacos_client.resolver import ServiceResolver

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
            raise RuntimeError(
                "NacosClient not initialized, call nacos_client.init() first"
            )
    return _nacos


def reset() -> None:
    """仅测试用：重置全局状态。"""
    global _nacos
    with _lock:
        _nacos = None
