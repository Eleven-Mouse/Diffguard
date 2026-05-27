# DiffGuard 仓库 — Codex 指引

本仓库为 [DiffGuard](https://github.com/Eleven-Mouse/Diffguard) AI 代码审查项目。在**本仓库**或**接入方仓库**启用 PR 自动审查时，按下列步骤操作。

## 接入方仓库必备 Secret

在 GitHub 仓库 **Settings → Secrets and variables → Actions** 添加：

| Secret | 说明 |
|--------|------|
| `DIFFGUARD_API_KEY` | LLM API Key（OpenAI 兼容） |
| `DIFFGUARD_API_BASE_URL` | 自定义 API 基址（代理/兼容端点） |

使用 CLI（需 `gh auth login`，值由维护者在本地传入，勿写入本文件或 Git）：

```bash
gh secret set DIFFGUARD_API_KEY --repo Eleven-Mouse/Diffguard
gh secret set DIFFGUARD_API_BASE_URL --repo Eleven-Mouse/Diffguard
```

## Workflow

在目标仓库创建 `.github/workflows/diffguard-review.yml`：

- **本仓库（Diffguard）**：使用本地 composite action `uses: ./`
- **其他仓库**：使用发布 tag，例如 `uses: Eleven-Mouse/Diffguard@v1.0.0`

Workflow 需 `permissions: contents: read` 与 `pull-requests: write`。

## 本仓库开发命令

```bash
# Java Gateway
cd services/gateway && mvn -DskipTests package && mvn verify

# Python Agent
cd services/agent && uv sync --dev && pytest && ruff check .

# 本地 PR 审查（需 GITHUB_TOKEN、DIFFGUARD_API_KEY）
export DIFFGUARD_API_KEY=sk-...
export DIFFGUARD_API_BASE_URL=test
java -jar services/gateway/target/diffguard-*.jar review --pr owner/repo#N --pipeline
```

## 目录要点

- `action.yml` — GitHub Composite Action 入口
- `services/gateway/` — Java 21：CLI、Tool Server、Orchestrator
- `services/agent/` — Python：Pipeline、`github_action_runner.py`
- `.github/workflows/` — CI 与 `diffguard-review.yml`

## Codex 行为约束

- 不要提交 `.env`、真实 API Key 或 token 到 Git 历史
- Secret 仅通过 `gh secret set` 或 GitHub UI 配置，勿写入 workflow 明文
- 修改 `action.yml` 或 agent 依赖时，同步检查 `requirements-github-action.txt`
- PR 仅改与任务相关的文件；不重构无关模块
