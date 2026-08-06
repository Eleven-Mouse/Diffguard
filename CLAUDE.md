# CLAUDE.md

Claude Code 在本仓库工作时，先按顺序读取这些文件：

1. `.codex/AGENTS.md` - 现有 Codex 指引、Secrets 红线、本仓库开发命令。
2. `BACKEND_GUIDE.md` - Java Gateway 与 Python Agent 的后端链路导读。
3. `README.md` / `README.zh-CN.md` - 产品能力、架构图、运行方式和环境变量。
4. `RELEASING.md` - 发布流程，仅在处理 release 相关任务时读取。

当前仓库尚未生成根级 `AGENTS.md`、`ARCHITECTURE.md`、`GLOSSARY.md`。若后续补齐这些文件，以根级 `AGENTS.md` 作为 AI 工作主入口。

## Hard Rules

- 不要写入 `.env`、真实 API Key、token 或 Secret；Secret 只能通过 GitHub UI 或 `gh secret set` 配置。
- 除非用户明确要求，不要执行 `git commit` / `git push`；[占位：请确认本项目是否有额外 Git 操作红线]。
- 只改任务相关文件；不要顺手重构无关模块、格式化全仓或清理历史代码。
- 修改 `action.yml` 或 Python Agent 依赖时，必须同步检查 `services/agent/requirements-github-action.txt`。
- Java Gateway 的主要边界在 `services/gateway`，Python Agent 的主要边界在 `services/agent`；跨边界改动要同时检查调用契约。
- Python 运行主链是 `services/agent/src/diffguard_agent/*`；不要新增与历史兼容入口并行的第二套行为。
- Tool 回调链通过 Python `tools/tool_client.py` 调 Java `/api/v1/tools/*`；改协议时必须同步 Java DTO/控制器与 Python 调用方。
- 改完 Java 代码优先运行 `cd services/gateway && mvn -B verify`。
- 改完 Python 代码优先运行 `cd services/agent && uv run pytest tests/ -v --tb=short`；涉及 lint 时再运行 `uv run ruff check src/ tests/`。
- 不要依赖本地 `.git/hooks` 作为仓库级保障；它们不随仓库分发。

## Useful Commands

| 目的 | 命令 |
|---|---|
| Java 构建与测试 | `cd services/gateway && mvn -B verify` |
| Java 跳过测试打包 | `cd services/gateway && mvn -DskipTests package` |
| Python 安装开发依赖 | `cd services/agent && uv sync --dev` |
| Python 测试 | `cd services/agent && uv run pytest tests/ -v --tb=short` |
| Python lint | `cd services/agent && uv run ruff check src/ tests/` |
| Docker 全栈 | `docker compose up -d` |

## Project Map

- `action.yml` - GitHub Composite Action 入口。
- `services/gateway/` - Java 21 + Maven，包含 CLI、Tool Server、Orchestrator、配置、LLM Provider、AST/CodeGraph/RAG 能力。
- `services/agent/` - Python 3.11+ / 3.12 运行目标，包含 FastAPI Agent、Pipeline、GitHub Action runner、误报过滤、工具调用客户端。
- `.github/workflows/` - CI、发布、手动测试和 DiffGuard 自审查 workflow。
- `docker-compose.yml` - Gateway、Agent、RabbitMQ 的本地/服务化编排。

## Known Gaps

- [占位：项目维护者希望 AI 每次大任务后必须跑的完整自检命令]
- [占位：项目反复踩坑或新人常犯错误]
- [占位：必须优先复用的公共工具类、封装或模式]
