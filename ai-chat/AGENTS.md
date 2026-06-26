# Repository Guidelines

## Project Structure & Module Organization

This is a Python chat-agent service. `main.py` is the Chainlit entry point for local prompt debugging. Core code lives in `agent/`: `builder.py` creates the LangChain/LangGraph agent, `grpc_server.py` runs the production gRPC service, `models.py` defines shared types, and subpackages hold prompts, middleware, tools, clients, and services. `nacos_client/` contains service discovery helpers. `env.example` holds the config template; design notes live in `docs/`.

## Build, Test, and Development Commands

- `uv sync` installs dependencies from `pyproject.toml` and `uv.lock`.
- `uv run chainlit run main.py` starts the local Chainlit UI.
- `uv run python -m agent.grpc_server` starts the gRPC service; it requires `.env`, PostgreSQL, and UserService or Nacos connectivity.
- `uv run python -m compileall main.py agent nacos_client` performs a syntax smoke check.

## Coding Style & Naming Conventions

Use Python 3.13 locally as declared in `pyproject.toml`. Follow PEP 8 with four-space indentation, grouped imports, and type hints on public functions. Use `snake_case` for modules, functions, and variables; use `PascalCase` for Pydantic models, TypedDicts, and service classes. Keep async code non-blocking in gRPC, Nacos, and external-service paths. No formatter or linter is configured, so match nearby code.

When editing prompts, preserve DH/BH terminology and the prompt-cache pattern documented in `CLAUDE.md`: static instructions first, dynamic user-specific data near the end.

## Testing Guidelines

No formal test suite is checked in. Add tests under `tests/`, mirror package paths, and name files `test_*.py`. Prefer `pytest` when introducing the test harness. Mock DeepSeek, Nacos, UserService, and PostgreSQL for unit tests; reserve live dependencies for explicit integration tests. For prompt or builder changes, run the compile smoke check and verify a targeted test or local Chainlit/gRPC interaction.

## Commit & Pull Request Guidelines

Recent history uses Conventional Commit-style subjects such as `feat(ai-chat): ...`, `fix(ai-chat): ...`, `perf(ai-chat): ...`, and `refactor: ...`. Keep subjects concise, imperative, and scoped when useful.

Pull requests should include a behavior summary, config changes, verification commands, and linked issues or merge requests. Include screenshots for Chainlit-facing changes and sample `grpcurl` output for RPC changes.

## Security & Configuration Tips

Start from `env.example`, but never commit `.env` or secrets. Runtime values include `DEEPSEEK_API_KEY`, PostgreSQL settings, and either `USER_SERVICE_ADDR` or Nacos settings. Keep private package index credentials in environment variables, not source files.
