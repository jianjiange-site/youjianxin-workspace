"""UserProfileService gRPC client wrapper.

Encapsulates channel lifecycle, timeout, error handling, and proto-to-dict
conversion so callers can work with plain UserInfo dicts.
"""

import logging

import grpc

from dating_proto_youjianxin_user import user_pb2, user_pb2_grpc
from core.models import UserInfo
from core.nacos_client.resolver import ServiceResolver
from core.time_utils import current_local_time

logger = logging.getLogger(__name__)

_GENDER_MAP = {0: "Unknown", 1: "Male", 2: "Female"}

# 仅连接类错误才重连重试；业务错误（NOT_FOUND/INVALID_ARGUMENT 等）直接抛
_RETRYABLE = {grpc.StatusCode.UNAVAILABLE, grpc.StatusCode.DEADLINE_EXCEEDED}


class UserServiceError(Exception):
    """Base exception for UserService communication errors."""


def _to_user_info(profile) -> UserInfo:
    return {
        "nickname": profile.nickname,
        "age": profile.age,
        "gender": _GENDER_MAP.get(profile.gender, "Unknown"),
        "height": profile.height,
        "bio": profile.bio,
        "occupation": profile.occupation,
        "education": profile.education,
        "location": profile.location,
        "birthday": profile.birthday,
        "interests": [
            {"tab_key": i.tab_key, "tag_key": i.tag_key}
            for i in profile.interests
        ],
        "city": profile.city,
        "state_code": profile.state_code,
        "race": profile.race,
        # 按用户所在地时区计算的模糊本地时间（lat/lng 优先，state_code 兜底）
        "current_time": current_local_time(
            profile.lat, profile.lng, profile.state_code
        ),
    }


class UserServiceClient:
    """Async gRPC client for user/UserService.

    Manages a single channel and stub for the lifetime of the server.  Exposes
    business-level methods that hide proto types and handle errors & timeouts.

    When a ``ServiceResolver`` is supplied, connection-class failures trigger a
    reconnect that picks up the latest address from Nacos.
    """

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

    def _connect(self, addr: str) -> None:
        self._addr = addr
        self._channel = grpc.aio.insecure_channel(addr)
        self._stub = user_pb2_grpc.UserProfileServiceStub(self._channel)

    async def _reconnect(self) -> None:
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

    async def __aenter__(self) -> "UserServiceClient":
        return self

    async def __aexit__(self, *exc_info) -> None:
        await self.close()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def get_user_info(self, user_id: int) -> UserInfo:
        """Fetch a single user's profile.

        Returns a ``UserInfo`` dict matching the format expected by
        ``agent.build_system_prompt``.

        Raises ``UserServiceError`` on gRPC failures.  On connection-class
        errors (``UNAVAILABLE``/``DEADLINE_EXCEEDED``) with a resolver
        configured, reconnects and retries once.
        """
        try:
            resp = await self._stub.GetProfile(
                user_pb2.GetProfileRequest(target_user_id=user_id),
                timeout=self._timeout,
            )
        except grpc.aio.AioRpcError as exc:
            if exc.code() not in _RETRYABLE:
                raise UserServiceError(
                    f"GetProfile({user_id}) failed: {exc.code().name}"
                ) from exc
            await self._reconnect()
            try:  # reconnect 后仅重试一次
                resp = await self._stub.GetProfile(
                    user_pb2.GetProfileRequest(target_user_id=user_id),
                    timeout=self._timeout,
                )
            except grpc.aio.AioRpcError as exc2:
                raise UserServiceError(
                    f"GetProfile({user_id}) failed after reconnect: {exc2.code().name}"
                ) from exc2

        return _to_user_info(resp.profile)

    async def batch_get_profiles(self, *user_ids: int) -> tuple[UserInfo, ...]:
        """Fetch multiple user profiles in a single batch call.

        Returns ``UserInfo`` dicts in the same order as the input *user_ids*.
        Raises ``UserServiceError`` on gRPC failures.
        """
        user_id_list = list(user_ids)
        try:
            resp = await self._stub.BatchGetProfile(
                user_pb2.BatchGetProfileRequest(target_user_ids=user_id_list),
                timeout=self._timeout,
            )
        except grpc.aio.AioRpcError as exc:
            if exc.code() not in _RETRYABLE:
                raise UserServiceError(
                    f"BatchGetProfile failed: {exc.code().name}"
                ) from exc
            await self._reconnect()
            try:
                resp = await self._stub.BatchGetProfile(
                    user_pb2.BatchGetProfileRequest(target_user_ids=user_id_list),
                    timeout=self._timeout,
                )
            except grpc.aio.AioRpcError as exc2:
                raise UserServiceError(
                    f"BatchGetProfile failed after reconnect: {exc2.code().name}"
                ) from exc2

        profile_map = {p.user_id: _to_user_info(p) for p in resp.profiles}
        missing = [uid for uid in user_ids if uid not in profile_map]
        if missing:
            raise UserServiceError(f"BatchGetProfile: user(s) not found: {missing}")
        return tuple(profile_map[uid] for uid in user_ids)

    async def get_bh_and_dh_users(
        self, bh_user_id: int, dh_user_id: int
    ) -> tuple[UserInfo, UserInfo]:
        """Fetch both BH and DH user profiles in a single batch call.

        Returns ``(bh_info, dh_info)``.  Either exception will be re-raised.
        """
        return await self.batch_get_profiles(bh_user_id, dh_user_id)

    async def close(self) -> None:
        """Close the underlying gRPC channel."""
        if self._channel is not None:
            await self._channel.close()
