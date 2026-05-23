# AGENTS.md

本文件作用于仓库根目录及全部子目录，作为 DiffGuard 项目的通用总入口规则。  
若子目录或专项文档（如 `JAVA.md`、`PYTHON.md`）已有更细粒度约束，则在不冲突前提下共同生效。

## 1. 任务入口规则

- 先判断任务归属：`services/gateway`（Java 网关）还是 `services/agent`（Python Agent），或两者联动。
- 涉及 CLI 命令、Webhook 接收、Tool Server、静态规则、Diff 收集，优先从 `services/gateway` 入手。
- 涉及 `PIPELINE` / `MULTI_AGENT` 编排、Agent 策略、Prompt、ToolClient，优先从 `services/agent` 入手。
- 涉及运行参数和环境变量，先核对 `shared/config/review-config-template.yml` 与 `ReviewConfig` / `app.config`，再改代码。

## 2. 方案澄清规则

- 凡是功能新增、模式切换、调用链调整（Java ↔ Python ↔ MQ ↔ DB），默认先给出简短澄清。
- 澄清至少覆盖三项：
  1. 目标：改哪条链路，预期行为是什么；
  2. 约束：兼容 CLI / Webhook / Docker 现有行为，不破坏已有模式；
  3. 方案：给出默认方案与最小改动点（类、方法、配置项）。
- 若请求已经明确且风险低，可直接执行；若关键信息缺失且会影响实现方向，先提问确认。

## 3. 执行边界

- 优先最小改动，尽量在既有分层内落地，不跨层“抄近路”。
- Java 侧默认沿着 `cli/adapter -> service -> domain -> infrastructure` 方向改动。
- Python 侧默认沿着 `main -> orchestrator -> agent/tool -> schema` 方向改动。
- 不在配置文件写明文密钥：LLM、Webhook、MQ、DB 凭据通过环境变量解析。

## 4. DiffGuard 项目硬约束（基于现有代码）

- 审查模式只有三种：`SIMPLE`、`PIPELINE`、`MULTI_AGENT`（见 `ReviewEngineFactory`）。
- Java Tool Server 采用会话机制，调用必须带 `X-Session-Id`，会话 TTL 10 分钟（`ToolSessionManager`）。
- Tool 端点是只读查询用途，默认不应引入写文件副作用（`file-content/diff-context/method-definition/call-graph/related-files/semantic-search`）。
- Webhook 链路必须保留签名校验与限流（`WebhookController`）。
- 静态规则引擎目前内置 4 条规则（`RuleEngine`）；新增规则应按同一入口扩展，不绕过扫描流程。
- RabbitMQ/DB 属于可降级依赖：不可用时要保持同步模式可运行（见 `ReviewOrchestrator`、`ReviewEngineFactory`）。

## 5. 代码与文档一致性

- 修改入口命令或 API 时，同步更新 `README.md` 的命令示例和说明。
- 修改配置结构时，同步更新：
  - `shared/config/review-config-template.yml`
  - Java `ReviewConfig` / `ConfigLoader`
  - Python `app/models/schemas.py`（如请求体字段受影响）
- 修改审查结果结构时，同步检查 Java `ReviewIssue/ReviewResult` 与 Python `IssuePayload/ReviewResponse` 对齐。

## 6. 提交前自检

- Java 变更：至少执行 `mvn test`（位于 `services/gateway`）。
- Python 变更：至少执行 `pytest`（位于 `services/agent`）。
- 联动链路变更：至少验证一次端到端调用（CLI 或 `/api/v1/review`），确认模式选择、会话创建、结果返回正常。

## 7. 进度文档维护约束

- 任何“完成状态”变更（任务完成、阶段完成、里程碑完成）后，必须在同一批改动中同步更新 `PROGRESS.md`。
- 更新 `PROGRESS.md` 时至少要改动以下内容：
  - `更新时间`
  - `进度总览`
  - 对应事项在 `已完成事项（Done）` / `进行中事项（In Progress）` / `待办事项（Next）` 的状态迁移
- 若存在新风险或阻塞，必须同步更新 `风险与阻塞`，不得延后到下一次提交。
