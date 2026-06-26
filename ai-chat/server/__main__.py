"""gRPC 服务入口：python -m server

读取 env、（可选）初始化 Nacos、构建 LLM，启动承载 ChatAgent + VisionAgent 的 gRPC server。
"""

import asyncio
import logging

from dotenv import load_dotenv

load_dotenv()

import core.nacos_client as nacos_client
from core.config import Settings
from core.llm import build_chat_llm
from server.bootstrap import start_server


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    settings = Settings.from_env()

    if settings.nacos_config:
        nacos_client.init(settings.nacos_config)  # 必须在 asyncio.run 之前

    asyncio.run(
        start_server(
            llm=build_chat_llm(),
            listen_addr=settings.listen_addr,
            db_uri=settings.db_uri,
            pool_min_size=settings.pool_min_size,
            pool_max_size=settings.pool_max_size,
            user_service_addr=settings.user_service_addr,
            nacos_config=settings.nacos_config,
            reply_language=settings.reply_language,
        )
    )


if __name__ == "__main__":
    main()
