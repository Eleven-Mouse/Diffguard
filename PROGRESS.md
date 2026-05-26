# DiffGuard 进度文档

更新时间：2026-05-26（补充执行策略原则：pipeline 为主，multi-agent 为辅）
负责人：待指定

## 1. 文档目标

用于跟踪 `services/gateway` Java 部分微服务化改造进度，确保拆分过程可追踪、可回滚、可验收。

## 2. 当前基线（已存在能力）

## 2.1 服务与入口

- 已有双服务形态：
  - Java：`services/gateway`
  - Python：`services/agent`
- Java 已支持：
  - CLI 入口（`review/install/uninstall/tool-server/orchestrator-server`）
  - Tool Server（`/api/v1/tools/*`）

## 2.2 核心链路

- CLI 链路：`ReviewCommand -> ReviewApplicationService -> ReviewEngineFactory -> ReviewEngine`
- Python 联动：`PythonReviewEngine -> /api/v1/review -> Python 回调 Java Tool Server`

## 2.3 可选基础设施

- RabbitMQ：任务分发
- Metrics：Micrometer 指标
- Resilience4j：熔断/重试/限流

## 2.4 执行策略原则

- 默认执行路径：`pipeline` 为主
- 增强能力定位：`multi-agent` 为辅（用于复杂分析、解释与建议增强）
- 落地约束：不以 `multi-agent` 替代主审查链路，优先保证稳定性与可回归性

## 3. 微服务拆分目标（Java）

第一阶段目标（低风险）：

1. 拆出 `tool-service`（仅承载 Tool Session + Tool API）
2. 保持 `gateway` 对外接口稳定（CLI 与 Tool/Orchestrator 命令不变）
3. 通过配置切换 Tool Server 地址，不影响 Python 端调用协议

第二阶段目标（中风险）：

1. 拆出 `review-orchestrator-service`
2. 网关仅做接入与转发，不承载重编排逻辑

第三阶段目标（可选）：

1. 拆出 `rule-service`
2. 拆出 `result-service`（结果查询与持久化）

## 4. 进度总览

| 项目 | 状态 | 说明 |
|---|---|---|
| 架构现状梳理 | 已完成 | 已确认 Java/Python 双服务与关键调用链 |
| 改造目标定义 | 已完成 | 已明确三阶段拆分顺序（先 Tool） |
| 进度文档落地 | 已完成 | 当前文件 `PROGRESS.md` |
| Tool Service 代码拆分 | 已完成 | 已新增独立启动命令 `tool-server`，并支持 `tool_service` 配置 |
| Orchestrator Service 拆分 | 进行中 | 迁移、适配层、契约实现、回归测试、旧 ReviewOrchestrator 下沉去重已完成；真实 MQ 联调受环境权限阻塞 |
| Session/Result/RAG 架构评估 | 已完成 | 已形成可执行评估与落地方案文档 |
| Rule/Result Service 拆分 | 未开始 | 可选阶段 |
| GitHub Action 审查链路稳健性增强 | 已完成 | 输出/错误分流、产物上传、Action 超时与 FP 开关参数接通 |
| GitHub Action 可选 Java Tool Server 接入 | 已完成 | 增加可选启动 Java Tool Server 并将 URL 透传至 Agent |
| Action-only 部署收敛 | 已完成 | docker-compose 停用 webhook 端口暴露与 webhook 相关环境变量 |
| Action-only 文档口径收敛 | 已完成 | README / README.zh-CN 同步为“Action-only + 可选 Tool Server”说明 |
| Webhook 链路代码移除 | 已完成 | 删除 Java webhook 包、server 子命令与相关测试，主入口切换为 CLI |
| 本地评审 PR-only 入口收敛 | 已完成 | CLI 改为必填 `--pr owner/repo#number`，移除旧 `--staged/--from/--to` 模式 |
| MULTI_AGENT 术语口径统一 | 已完成 | 统一为“兼容入口（当前回退到 Pipeline）”，避免误解为独立编排已落地 |
| 执行策略原则固化 | 已完成 | 明确“pipeline 为主，multi-agent 为辅”并写入进度文档 |
| README 目录与审查器命名对齐 | 已完成 | README / README.zh-CN 同步到 `src/diffguard_agent` 路径与 `security/logic/quality` 术语 |
| GitHub Action 落地阻塞修复 | 已完成 | 补齐 `tools/tool_client.py` 与 `tools/definitions.py`，去除 `diffguard_agent/__init__.py` 启动副作用，修复 Action 运行时导入阻塞 |
| GitHub Action 权限文档补齐 | 已完成 | README / README.zh-CN 增加 `permissions: contents: read + pull-requests: write` 示例，并补充 README Action 使用片段 |
| ReAct/多Agent/Safety 架构对照评审 | 已完成 | 已基于代码逐条给出已实现机制与缺口清单，形成可执行改进方向 |

## 5. 已完成事项（Done）

1. 新增总约束文档：`AGENTS.md`
2. 新增后端导读文档：`BACKEND_GUIDE.md`
3. 完成 Java 端微服务化拆分方案口径统一（先 Tool，后 Orchestrator）
4. 完成第一阶段代码改造：Tool Server 可独立进程启动，并保持 Python Tool 协议兼容
5. 完成 `README.md`、`review-config-template.yml` 对应说明更新
6. 明确外部 RAG 引入策略：采用适配器渐进接入，不做一次性替换
7. 完成 `review-orchestrator-service` 拆分边界与同步/MQ 契约设计（`ORCHESTRATOR_CONTRACT.md`）
8. 完成 `orchestrator-server` 命令与基础 API 骨架（创建任务/查状态/查结果）
9. 完成重构基线文档：`ARCHITECTURE_CURRENT.md`、`ARCHITECTURE_TARGET.md`、`MIGRATION_PLAN.md`
10. 完成 gateway -> orchestrator 远程调用接入（含 legacy fallback 开关）
11. 完成 Orchestrator Day3 改造：DTO 化接口、统一错误码、`X-Idempotency-Key` 幂等处理
12. 完成 Orchestrator Day4 核心改造：MQ 任务发布、结果消费、`task_id/request_id` 兼容关联、失败消息 Nack 入 DLQ、本地同步降级兜底
13. 完成 Day5 文档收敛：新增第一轮验收记录 `REFACTOR_ACCEPTANCE_ROUND1.md`，并在 `README.md` 增加重构进展入口
14. 完成无状态化基础设施收敛：移除 Java/Python 对 MySQL、Redis 的依赖与配置入口，统一为“仅可选 RabbitMQ”
15. 完成向量存储重构：接入 `ChromaVectorStore`，移除旧 `InMemoryVectorStore` 代码并完成配置模板更新
16. 完成 Python 侧文档上下文入库骨架：新增 `unstructured -> ChromaDB` 的 `context/ingest` 接口与配置项
17. 完成 `ReviewOrchestrator` 主执行链路迁移：CLI + Webhook 均可通过 `ReviewExecutionAdapter` 在 `remote/legacy` 间切换
18. 完成 gateway 面向 orchestrator 的调用适配层：新增 `ReviewExecutionAdapter`，封装 remote 调用与 legacy fallback
19. 完成 `ORCHESTRATOR_CONTRACT.md` 的实现收敛：Controller 输入校验、错误码、幂等冲突校验、`allowed_files` MQ 透传、`task_id/request_id` 兼容
20. 完成 remote/legacy 回归测试：新增 `ReviewExecutionAdapterTest` 与 `ReviewOrchestratorServerContractTest`
21. 完成本轮验收记录：`REFACTOR_ACCEPTANCE_ROUND2.md`
22. 完成任务评估文档：`RAG_RESULT_SESSION_EVALUATION.md`（Tool Session 多实例一致性、result-service 所有权边界、外部 RAG 适配方案与 A/B 对比结论）
23. 完成旧 `ReviewOrchestrator` 下沉：移除 AST/规则/Resilience 重复编排，统一走 `ReviewExecutionAdapter`（remote/legacy），并补齐定向回归测试通过
24. 完成 Java 目录/包命名去 DDD 收敛：`adapter/domain/infrastructure/service` 迁移为 `webhook/toolserver/orchestrator/review/agent/platform` 业务域结构，并通过关键链路回归测试
25. 完成 GitHub Action 审查链路稳健性增强：`review-output.json` 与 `review-error.log` 分流、`results-file` 输出、审查产物上传（artifact）
26. 完成 Action 参数生效接通：`DIFFGUARD_TIMEOUT_MINUTES` -> `llm_config.timeout_seconds`，`DIFFGUARD_ENABLE_FP_FILTER` -> pipeline FP 过滤阶段可开关
27. 完成 GitHub Action 可选 Java Tool Server 接入：新增 `use-java-tool-server` / `tool-server-url` 输入，支持在 Action 中构建并启动 Java Tool Server，并透传 `tool_server_url` 给 Agent
28. 完成 Action-only 部署收敛改造：`docker-compose.yml` 移除 `8090:8080` webhook 端口映射，并删除 gateway 的 `DIFFGUARD_WEBHOOK_SECRET` / `DIFFGUARD_GITHUB_TOKEN` 注入
29. 完成 Action-only 文档收敛：`README.md` / `README.zh-CN.md` 更新为“Action-only 默认部署 + 可选 Tool Server”，并同步 Docker Compose 示例与环境变量说明
30. 完成 Webhook 链路代码移除：删除 `services/gateway/src/main/java/com/diffguard/webhook/*`、`ServerCommand`、`ReviewOrchestrator` 及其对应测试；`pom.xml` shade 主类切换至 `com.diffguard.cli.DiffGuardMain`
31. 完成本地评审 PR-only 入口收敛：`review` 子命令新增必填 `--pr`，`ReviewApplicationService` 改为基于 GitHub PR API 收集 diff，移除 `--staged/--from/--to` 本地模式入口
32. 完成 MULTI_AGENT 术语口径统一：更新 `README.md` / `README.zh-CN.md` / `BACKEND_GUIDE.md` 与 Python 入口注释，明确 `MULTI_AGENT` 当前为兼容入口并复用 Pipeline 链路
33. 完成 README 目录与审查器命名对齐：同步 `services/agent/src/diffguard_agent` 主路径与 `security/logic/quality` 审查器口径，移除 `ReviewAgent/AgentRegistry` 作为当前可用扩展点的误导表述
34. 完成 GitHub Action 运行链路阻塞修复：新增 `services/agent/src/diffguard_agent/tools/tool_client.py` 与 `services/agent/src/diffguard_agent/tools/definitions.py`，打通 reviewer 工具调用与 tool session 生命周期
35. 完成 Action 启动副作用修复：`services/agent/src/diffguard_agent/__init__.py` 移除对 `main` 的强依赖导入，避免 `python -m diffguard_agent.github_action_runner` 触发 FastAPI 依赖阻塞
36. 完成 reviewer 工具封装兼容修复：`ReviewerStage` 内将工具函数转换为 `StructuredTool`，兼容 `create_tool_calling_agent` 与现有测试契约
37. 完成 Action 关键链路回归测试：本地通过 `test_tools_tool_client.py`、`test_tools_definitions.py`、`test_github_action_runner.py`、`test_agent_pipeline_orchestrator.py`、`test_pipeline_stages.py`
38. 完成 Action 权限文档补齐：`README.md` / `README.zh-CN.md` 新增 GitHub Action `permissions` 配置与完整接入示例
39. 完成 ReAct 循环、多Agent协作、Safety/Alignment 三类问题项目级对照评审：已沉淀“现状能力 + 缺口”清单，明确后续应补齐的循环检测、策略治理与多Agent编排增强项
40. 完成执行策略原则固化：在进度文档明确“pipeline 为主，multi-agent 为辅”，并约束 multi-agent 作为增强能力使用

## 6. 进行中事项（In Progress）

1. 执行任务 6：orchestrator 与 Python agent 真实 MQ 联调（成功/失败/DLQ）并补证据，当前受 Docker 服务权限阻塞
2. 按评估文档推进 `SemanticRetrieverAdapter` 抽象与 `rag-anything` sidecar PoC（保持 Tool 协议不变）

## 7. 待办事项（Next）

优先级 P0：

1. 完成 orchestrator 与 Python agent 的一轮真实 MQ 联调并补全验收证据（成功链路、失败链路、DLQ 观测）

优先级 P1：

1. 实施 Tool Session 多实例一致性方案第一阶段（`X-Session-Id` 一致性路由）
2. 明确 `result-service` 事件模型并完成读写边界草案评审
3. 落地外部 RAG 适配器 PoC（`coderag` / `rag-anything` 双通道）
4. 执行外部 RAG 与现有 `review/coderag` 的实测 A/B（输出 Recall@K/P95/成本/稳定性）

## 8. 风险与阻塞
1. `services/agent` 目录下 `app/` 与 `diffguard/` 路径仍存在迁移残留风险，需确保运行入口唯一，避免行为偏差。
2. 现有 Tool 会话是 JVM 内内存态（TTL 10 分钟），拆成独立服务后需要评估会话容量与清理策略。
3. MQ 为可选依赖，拆分后仍需保持“不可用可降级”的现有行为。
4. 仓库当前存在较多历史失败测试（与本次改造无直接关系），暂以“可编译 + 关键链路验证”作为阶段验收依据。
5. 若直接替换现有 `coderag` 实现，可能破坏 `semantic_search` 工具链路与会话隔离约束，需采用“适配器 + 渐进切换”。
6. 当前执行环境无法启动 Docker Desktop Service（`com.docker.service`），导致 RabbitMQ 容器无法拉起，阻塞真实 MQ 联调验收。
7. 仓库首页仍引用不存在的 `README_EN.md`，中英文入口存在断链风险，建议补齐英文文档或修正入口链接。

## 9. 验收标准（第一阶段）

第一阶段（Tool Service）验收通过条件：

1. `--pipeline`、`--multi-agent` 两种模式可正常完成审查
2. Python 端 Tool 调用协议不变（Header 与路径保持兼容）
3. 关键自动化测试可通过（Java `mvn test`，Python `pytest`）

## 10. 更新约束（必须执行）

每完成一项进度（任务/阶段/里程碑）后，必须在同一批改动中同步更新本文件，至少包括：

1. `更新时间`
2. `进度总览` 对应状态
3. `Done / In Progress / Next` 的条目迁移
4. 若有新增问题，同步更新 `风险与阻塞`
