"""集中读取环境变量配置，供 server 启动层使用。

把原先散落在 grpc_server.__main__ 的 os.environ 读取收敛到一处。
"""

import os
import re
from dataclasses import dataclass

from core.nacos_client import NacosConfig


@dataclass
class Settings:
    listen_addr: str
    db_uri: str
    pool_min_size: int
    pool_max_size: int
    user_service_addr: str
    nacos_config: NacosConfig | None
    reply_language: str | None

    @classmethod
    def from_env(cls) -> "Settings":
        listen_addr = os.environ.get("GRPC_LISTEN_ADDR", "[::]:50051")
        db_uri = (
            f"postgresql://{os.environ['PG_USER']}:{os.environ['PG_PASSWORD']}"
            f"@{os.environ.get('PG_HOST', '38.76.188.242')}:{os.environ.get('PG_PORT', '5433')}"
            f"/{os.environ.get('PG_DB', 'youjianxin-dating-dev')}"
        )
        return cls(
            listen_addr=listen_addr,
            db_uri=db_uri,
            pool_min_size=int(os.environ.get("PG_POOL_MIN_SIZE", "2")),
            pool_max_size=int(os.environ.get("PG_POOL_MAX_SIZE", "10")),
            user_service_addr=os.environ.get(
                "USER_SERVICE_ADDR", "user-service:8080"
            ),
            nacos_config=cls._build_nacos_config(listen_addr),
            # 让 DH 改说指定语言（如 Chinese）；留空=遵循 system.md 的 English only
            reply_language=os.environ.get("DH_REPLY_LANGUAGE") or None,
        )

    @staticmethod
    def _build_nacos_config(listen_addr: str) -> NacosConfig | None:
        """配置了 NACOS_SERVER_ADDR 才接入 Nacos，否则回退静态直连。"""
        server_addr = os.environ.get("NACOS_SERVER_ADDR")
        if not server_addr:
            return None
        port_match = re.search(r":(\d+)$", listen_addr)
        service_port = int(port_match.group(1)) if port_match else 50051
        return NacosConfig(
            server_addr=server_addr,
            namespace=os.environ.get("NACOS_NAMESPACE", "youjianxin-dating-dev"),
            group=os.environ.get("NACOS_GROUP", "DEFAULT_GROUP"),
            service_name=os.environ.get("NACOS_SERVICE_NAME", "ai-chat"),
            service_port=service_port,
            username=os.environ.get("NACOS_USERNAME", "nacos"),
            password=os.environ.get("NACOS_PASSWORD", "nacos"),
        )
