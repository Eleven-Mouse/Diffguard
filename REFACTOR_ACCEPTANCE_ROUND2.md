# Refactor Acceptance Round 2

更新时间：2026-05-23  
范围：`review-orchestrator-service` 迁移与 gateway 适配收敛（任务 2-6）

## 1. 本轮目标

1. 将 `ReviewOrchestrator` 执行链路迁移到独立 orchestrator 服务能力（可 remote 化）。
2. 为 gateway 增加面向 orchestrator 的调用适配层。
3. 按 `ORCHESTRATOR_CONTRACT.md` 落实 DTO/Controller/错误码/幂等。
4. 增补 remote/legacy 切换回归测试。
5. 完成 orchestrator 与 Python agent 的真实 MQ 联调证据。

## 2. 交付结果

## 2.1 代码改造（已完成）

1. 新增 gateway 调用适配层：`ReviewExecutionAdapter`  
   - `remote`：通过 `OrchestratorClient` 调用独立 orchestrator  
   - `legacy`：本地 `ReviewEngineFactory.create` 回退

2. `ReviewApplicationService` 改为统一走适配层（CLI 链路已接入）。

3. `ReviewOrchestrator`（Webhook 链路）改为通过 `ReviewExecutionAdapter` 执行评审，支持 `orchestrator.mode=remote` 下委托独立服务。

4. `OrchestratorClient` 增强：  
   - 自动携带 `X-Idempotency-Key`  
   - 错误响应 message 提取增强（便于排障）

5. `ReviewOrchestratorServer` 增强：  
   - `mode` 白名单校验（`SIMPLE/PIPELINE/MULTI_AGENT`）  
   - 幂等 hash 纳入 `tool_server_url/repo/pr/head_sha/allowed_files`  
   - MQ 透传 `allowed_files`

6. MQ 契约兼容增强：  
   - Java `ReviewTaskMessage`：`task_id/request_id` 双字段、`allowed_files` 透传  
   - Java `RabbitMQConfig`：新增 `review.multi_agent.task` 绑定（保留 `review.agent.task` 兼容）  
   - Python `rabbitmq_consumer`：精确 routing key 绑定、`task_id/request_id` 回退关联

## 2.2 回归测试（已完成）

新增测试：
1. `ReviewExecutionAdapterTest`（remote 成功 / remote 失败回退 legacy / remote 失败不回退）
2. `ReviewOrchestratorServerContractTest`（400/422/409 错误码、幂等复用）

执行命令与结果：

```powershell
mvn "-Dtest=ReviewExecutionAdapterTest,ReviewOrchestratorServerContractTest" test
```

结果：`BUILD SUCCESS`，7 tests passed。

## 3. MQ 联调结果（任务 6）

## 3.1 目标

完成 RabbitMQ + orchestrator + Python agent worker 的真实联调并沉淀：
1. 成功链路
2. 失败链路
3. DLQ 观测

## 3.2 阻塞现状

当前环境无法启动 RabbitMQ 容器，阻塞真实 MQ 联调：

```text
docker compose up -d rabbitmq
unable to get image 'rabbitmq:3.13-management': failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
```

进一步检查：
```text
Get-Service com.docker.service -> Status: Stopped
Start-Service com.docker.service -> cannot open service on computer '.'
```

结论：当前会话无权限启动 Docker 服务，且本机无可用 RabbitMQ（`localhost:5672` 不可达），任务 6 暂时受环境权限阻塞。

## 3.3 已做的联调前置

1. Python worker 增加 `AGENT_MQ_MOCK_MODE=success`（默认 `off`）用于无 LLM 凭据场景下的 MQ 通道验收准备。
2. 新增 `services/agent/app/__init__.py` 导入兼容桥接，确保 `app.*` 旧路径可用，便于直接启动 worker 进行 MQ 验证。

## 4. 待解除阻塞后的一键联调建议

1. 启动 RabbitMQ（Docker 或外部实例）。
2. 启动 orchestrator-server（`message_queue.enabled=true`）。
3. 启动 Python worker（`AGENT_MODE=worker`）。
4. 发起 `PIPELINE/MULTI_AGENT` 任务，验证：  
   - 成功：`status=completed`  
   - 失败：构造非法 mode 或 tool session 异常  
   - DLQ：发送缺失 `task_id/request_id` 的 result 消息，观测 `review.dlq`

