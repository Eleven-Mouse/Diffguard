# 后端篇：`DiffGuard` 项目导读

## 1. 先用一句话理解后端

`DiffGuard` 不是单一服务，而是一个 **Java Gateway + Orchestrator + Python Agent** 的协同后端：  
Java Gateway 负责入口与调用适配，Orchestrator 负责审查编排，Python 负责 Pipeline 审查执行（`MULTI_AGENT` 当前为兼容入口，复用 Pipeline 链路）。

## 2. 模块结构怎么理解

根目录核心结构：

- `services/gateway`：Java 21，CLI + Webhook + Tool Server + Orchestrator 调用适配
- `services/agent`：Python 3.11+，FastAPI + LangChain 编排执行
- `shared/config`：配置模板（`review-config-template.yml`）

建议把主调用链理解成：

```text
CLI (Java)
  -> Diff 收集 + AST 增强
  -> ReviewExecutionAdapter
      remote: 调 orchestrator-service
      legacy: 本地引擎执行

Webhook (Java)
  -> 验签/限流/事件过滤
  -> ReviewOrchestrator (薄层异步触发)
  -> git fetch + diff 收集
  -> ReviewExecutionAdapter
  -> GitHub 评论回写
```

## 3. Java Gateway（`services/gateway`）如何读

### 3.1 入口层

- `com.diffguard.DiffGuard`：主入口
- `com.diffguard.cli.DiffGuardMain`：顶层命令注册
- `review/install/uninstall/server` 子命令在 `com.diffguard.cli.*`

重点：先搞清楚命令怎么触发，再看具体编排。

### 3.2 编排层

- `ReviewApplicationService`：CLI 模式“配置加载 -> diff 收集 -> AST 增强 -> 统一审查调用”
- `ReviewExecutionAdapter`：网关统一审查调用适配（`remote/legacy` 切换 + fallback）
- `ReviewOrchestrator`：Webhook 薄层异步触发（git fetch + diff + 结果评论）
- `ReviewEngineFactory`：`SIMPLE/PIPELINE/MULTI_AGENT` 选择与构建

重点：业务流程在这里串起来，但核心规则不应堆在这里。

### 3.3 适配层

- Webhook：`webhook/*`
  - 签名校验：`SignatureVerifier`
  - 限流：`RateLimiter`
  - 事件处理：`WebhookController`
- Tool Server：`toolserver/*`
  - 会话：`ToolSessionManager`（TTL 10 分钟）
  - 端点：`ToolServerController`
- Orchestrator API：`orchestrator/*`
  - 任务创建/状态/结果接口：`ReviewOrchestratorServer`

重点：这是 Java 对外协议边界，改动要注意兼容性。

### 3.4 领域能力

- `review/*`：审查引擎与编排调用核心
- `review/rules/RuleEngine`：静态规则扫描（SQL 注入、硬编码密钥、危险函数、复杂度）
- `review/ast/*`：AST 分析与增强
- `review/codegraph/*`：代码图谱
- `review/coderag/*`：语义检索与向量存储
- `agent/tools/*`：6 个 Agent 工具与文件沙箱

重点：规则与分析能力主要在这里。

### 3.5 基础设施

- `platform/config/*`：三层配置加载与校验
- `platform/llm/*`：LLM 调用与 provider
- `platform/git/*`：Diff 收集、Hook 安装
- `platform/messaging/*`：RabbitMQ 发布/消费
- `platform/observability/*`：Micrometer 指标

重点：关注“怎么接外部系统”，不要在这里写业务判定。

## 4. Python Agent（`services/agent`）如何读

## 4.1 当前代码结构要点

`services/agent` 当前运行主路径是 `src/diffguard_agent/*`；  
`services/agent/diffguard/*` 主要是兼容入口与历史迁移残留。

重点：新增功能优先延续 `src/diffguard_agent/*` 主链，避免双目录行为分叉。

## 4.2 HTTP 与 Worker 入口

- `src/diffguard_agent/main.py`
  - `GET /api/v1/health`
  - `POST /api/v1/review`
  - 按配置可同时启动 RabbitMQ consumer（`AGENT_MODE`）

## 4.3 当前编排器

- `PipelineOrchestrator`
  - 默认阶段：`SummaryStage -> ReviewerStage -> AggregationStage -> FalsePositiveFilterStage`
  - 阶段顺序执行；超大 PR 自动分片后逐片执行并合并结果
- `MULTI_AGENT` 模式说明
  - API 枚举仍保留 `MULTI_AGENT`
  - 当前入口实现会将其回退到 `PipelineOrchestrator` 执行（兼容行为）

## 4.4 Tool 回调链

Python 侧通过 `src/diffguard_agent/tools/tool_client.py`：

1. `create_tool_session` 到 Java `/api/v1/tools/session`
2. 执行工具调用（带 `X-Session-Id`，可带 `X-Tool-Secret`）
3. `destroy_tool_session` 释放会话

这是 Python 获取代码上下文的唯一正式入口。

## 5. 典型请求链路怎么走

## 5.1 CLI 审查链

```text
java -jar ... review --pr owner/repo#number
  -> ReviewCommand
  -> ReviewApplicationService.collectAndEnrich
  -> ReviewEngineFactory.resolveEngineType
  -> ReviewEngine.review
  -> ReviewReportPrinter 输出
```

## 5.2 Webhook 审查链

```text
POST /webhook/github
  -> WebhookController (签名 + 限流 + 事件过滤)
  -> ReviewOrchestrator.processAsync
  -> git fetch + diff
  -> ReviewExecutionAdapter (remote or legacy)
  -> 回写 GitHub PR 评论
```

## 5.3 Java-Python 联动链（PIPELINE + MULTI_AGENT 兼容）

```text
Java PythonReviewEngine -> Python /api/v1/review
  -> Python orchestrator 创建 tool session
  -> Python 调 Java /api/v1/tools/*
  -> Python 返回 issues/summary
  -> Java 聚合并输出
```

## 6. 配置体系怎么用

- Java 默认 `ConfigLoader.load(projectDir)`：  
  内置模板 -> 项目根 `application.yml` -> 用户主目录 `application.yml`（深度合并）。
- 模板文件：`shared/config/review-config-template.yml`
- 关键环境变量：
  - `DIFFGUARD_API_KEY`
  - `DIFFGUARD_API_BASE_URL`（可选）
  - `DIFFGUARD_AGENT_URL`
  - `DIFFGUARD_WEBHOOK_HMAC_SECRET`
  - `GITHUB_TOKEN`
  - `DIFFGUARD_TOOL_SECRET`
  - `RABBITMQ_*`

## 7. 测试与运行命令

Java：

- `cd services/gateway`
- `mvn clean package -DskipTests`
- `mvn test`

Python：

- `cd services/agent`
- `pytest`

本地常用审查命令（Java CLI）：

- `java -jar target/diffguard-1.0.0.jar review --pr owner/repo#123`
- `java -jar target/diffguard-1.0.0.jar review --pr owner/repo#123 --pipeline`
- `java -jar target/diffguard-1.0.0.jar review --pr owner/repo#123 --multi-agent`（兼容入口，当前复用 pipeline）

## 8. 新人推荐阅读顺序

1. `README.md`
2. `services/gateway/src/main/java/com/diffguard/cli/ReviewCommand.java`
3. `services/gateway/src/main/java/com/diffguard/review/ReviewApplicationService.java`
4. `services/gateway/src/main/java/com/diffguard/review/ReviewEngineFactory.java`
5. `services/gateway/src/main/java/com/diffguard/toolserver/ToolServerController.java`
6. `services/gateway/src/main/java/com/diffguard/review/rules/RuleEngine.java`
7. `services/agent/src/diffguard_agent/main.py`
8. `services/agent/src/diffguard_agent/agent/pipeline_orchestrator.py`
9. `services/agent/src/diffguard_agent/agent/pipeline/stages/reviewer.py`
10. `services/agent/src/diffguard_agent/tools/tool_client.py`

## 9. 你最容易踩的坑

1. 把 Webhook 的旧重编排逻辑还放在 gateway  
当前 gateway 的 `ReviewOrchestrator` 已是薄层触发，重编排职责应落在 orchestrator-service。

2. 忽略 Tool Session 生命周期  
不创建/不销毁 session 会直接导致工具调用失败或资源泄露风险。

3. 配置只改一边  
字段变更常常要同步 Java 配置类、Python schema 与模板文件。

4. 假设 MQ/DB 一定可用  
当前代码就是“可选依赖 + 自动降级”，改动不能破坏降级路径。

5. 在 `services/agent` 双目录结构里随意改  
应明确改动目标路径（当前运行主链是 `src/diffguard_agent/*`）。

## 10. 一句话建议

先把 “`ReviewCommand -> ReviewApplicationService -> ReviewEngineFactory -> Python orchestrator -> Tool Server`” 这条主链读通，再做功能改动，成功率最高。
