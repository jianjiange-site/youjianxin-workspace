# syntax=docker/dockerfile:1.7
# ai-chat 模块镜像。被根 deploy/docker-compose.dev.yml 引用构建(无独立 compose)。
# 入口 python -m server,单进程承载 ChatAgent + VisionAgent gRPC。

FROM python:3.13-slim

# uv 管理依赖
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /usr/local/bin/

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PATH="/app/.venv/bin:$PATH"

WORKDIR /app

# 先拷依赖描述,利用层缓存(proto 包 + 全依赖从 pyproject 配的 Nexus pypi-group 拉)
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv,id=ai-chat-uv \
    uv sync --frozen --no-install-project --no-dev

# 再拷源码并安装项目本身
COPY . .
RUN --mount=type=cache,target=/root/.cache/uv,id=ai-chat-uv \
    uv sync --frozen --no-dev

EXPOSE 50051

CMD ["python", "-m", "server"]
