"""gRPC 启动层：单进程承载多 servicer，PG pool / Nacos / 优雅停机。"""

import asyncio
import logging
import signal

import grpc
from grpc_reflection.v1alpha import reflection

from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from psycopg_pool import AsyncConnectionPool

from dating_proto_youjianxin_chat import chat_pb2, chat_pb2_grpc

import core.nacos_client as nacos_client
from core.nacos_client import NacosConfig, ServiceResolver
from core.clients import UserServiceClient
from chat_agent.servicer import ChatAgentServicer

logger = logging.getLogger(__name__)


def _register_vision(server) -> str | None:
    """尝试注册 VisionAgent servicer，返回其 full_name；缺依赖/缺 key 则降级跳过。

    依赖 dating-proto-youjianxin-vision（Nexus 发布后可用）与 GROQ_API_KEY / GOOGLE_API_KEY，
    任一缺失时只跑 ChatAgent，不影响 chat 服务。
    """
    try:
        from dating_proto_youjianxin_vision import vision_pb2, vision_pb2_grpc
        from vision_agent.servicer import VisionAgentServicer

        vision_pb2_grpc.add_VisionAgentServicer_to_server(VisionAgentServicer(), server)
        logger.info("VisionAgent servicer registered")
        return vision_pb2.DESCRIPTOR.services_by_name["VisionAgent"].full_name
    except Exception as exc:
        logger.warning("VisionAgent not registered (vision stub / API key missing?): %s", exc)
        return None


async def start_server(
    llm,
    listen_addr: str = "[::]:50051",
    db_uri: str = None,
    pool_min_size: int = 2,
    pool_max_size: int = 10,
    shutdown_grace: float = 10.0,
    nacos_config: NacosConfig | None = None,
    user_service_addr: str = "",
    reply_language: str | None = None,
):
    """启动 gRPC server，阻塞运行，收到 SIGINT/SIGTERM 后优雅停机。"""
    async with AsyncConnectionPool(
        db_uri,
        min_size=pool_min_size,
        max_size=pool_max_size,
        open=False,
        check=AsyncConnectionPool.check_connection,  # 借出前探活，死连接自动丢弃重建
        kwargs={
            "autocommit": True,
            "prepare_threshold": 0,
            # TCP 层保活：闲置 60s 后开始探测，防止 NAT/防火墙静默清掉空闲连接
            "keepalives": 1,
            "keepalives_idle": 60,
            "keepalives_interval": 10,
            "keepalives_count": 3,
        },
    ) as pool:
        await pool.wait()
        checkpointer = AsyncPostgresSaver(conn=pool)
        await checkpointer.setup()  # 幂等建表，重启安全

        # Nacos：取已初始化的 client（init 在入口完成），为依赖服务创建 resolver
        nacos = nacos_client.get() if nacos_config else None
        resolver = None
        if nacos:
            resolver = ServiceResolver(nacos, "user-service")
            resolved_addr = resolver.current()
        else:
            resolved_addr = user_service_addr

        user_client = UserServiceClient(addr=resolved_addr, resolver=resolver)

        server = grpc.aio.server()

        # ── ChatAgent servicer ──────────────────────────────────
        chat_pb2_grpc.add_ChatAgentServicer_to_server(
            ChatAgentServicer(
                llm=llm,
                user_client=user_client,
                checkpointer=checkpointer,
                reply_language=reply_language,
            ),
            server,
        )

        # ── VisionAgent servicer（无状态，无需 PG / user_client）──
        # 可选启用：依赖 dating-proto-youjianxin-vision（发布到 Nexus 后生效）+ GROQ/GOOGLE key。
        # 任一缺失时降级为只跑 ChatAgent，不阻塞 chat 部署。
        vision_full_name = _register_vision(server)

        service_names = tuple(
            name
            for name in (
                chat_pb2.DESCRIPTOR.services_by_name["ChatAgent"].full_name,
                vision_full_name,
                reflection.SERVICE_NAME,
            )
            if name
        )
        reflection.enable_server_reflection(service_names, server)

        server.add_insecure_port(listen_addr)

        stop_event = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, stop_event.set)
            except NotImplementedError:
                pass

        logger.info("gRPC server listening on %s", listen_addr)
        try:
            await server.start()
            if nacos:
                nacos.register()
                logger.info("Registered '%s' to Nacos", nacos_config.service_name)
            await stop_event.wait()
        finally:
            logger.info("Shutting down (grace=%.1fs)", shutdown_grace)
            if nacos:
                nacos.deregister()
                if resolver:
                    resolver.close()
                nacos.stop_subscribe()  # 停止 subscribe 轮询线程
            await server.stop(shutdown_grace)
            await user_client.close()
