# Review-Orchestrator Service 输入输出契约（同步 + MQ）

更新时间：2026-05-23  
适用范围：`gateway-service` ↔ `review-orchestrator-service` ↔ `python-agent-service`

## 1. 设计目标

1. 明确 `review-orchestrator-service` 对外同步接口契约（任务创建/状态/结果）。
2. 明确编排器与 Agent 之间的异步 MQ 契约（Task/Result）。
3. 与当前代码保持兼容：`task_id`、`request_id`、三种审查模式、可降级 MQ。

## 2. 服务边界

`review-orchestrator-service` 负责：
1. 任务状态机：`PENDING/RUNNING/COMPLETED/FAILED`
2. 编排流程：AST enrich、规则扫描、引擎分发（`SIMPLE/PIPELINE/MULTI_AGENT`）
3. 异步模式下的 MQ 任务投递和结果消费
4. 对外统一查询任务状态与结果

`review-orchestrator-service` 不负责：
1. Webhook 验签/限流（`gateway-service`）
2. Tool API 的读接口执行（`tool-service`）
3. Agent 内部提示词和多 Agent 策略（`python-agent-service`）

## 3. 状态与幂等

状态定义：
1. `PENDING`：任务已入库，未开始
2. `RUNNING`：任务处理中（本地执行或已投递 MQ）
3. `COMPLETED`：成功，结果可取
4. `FAILED`：失败，`error` 可读

幂等定义：
1. Header：`X-Idempotency-Key`（推荐 `repo:pr:head_sha:mode`）
2. 同 key 同 payload：返回既有 `task_id`
3. 同 key 不同 payload：返回 `409 IDEMPOTENCY_CONFLICT`

## 4. 同步 HTTP 契约（Gateway -> Orchestrator）

### 4.1 创建任务

`POST /api/v1/orchestrator/reviews`

请求头：
1. `Content-Type: application/json`
2. `X-Trace-Id`（可选，未传则服务端生成）
3. `X-Idempotency-Key`（可选但强烈建议）

请求体：

```json
{
  "mode": "SIMPLE | PIPELINE | MULTI_AGENT",
  "project_dir": "C:/repo",
  "tool_server_url": "http://localhost:9090",
  "diff_entries": [
    {
      "file_path": "src/A.java",
      "content": "@@ ...",
      "token_count": 123
    }
  ],
  "repo_name": "owner/repo",
  "pr_number": 123,
  "head_sha": "abcdef",
  "allowed_files": ["src/A.java"]
}
```

字段约束：
1. `mode` 必填，且仅允许 `SIMPLE/PIPELINE/MULTI_AGENT`
2. `tool_server_url` 必填（422）
3. `diff_entries` 必填且非空（400）
4. 不允许在请求体透传明文 `api_key`

成功响应（202）：

```json
{
  "task_id": "4b2f2e95-16f4-4bca-8b74-e4c019c16bb7",
  "status": "PENDING | RUNNING | COMPLETED | FAILED",
  "review_mode": "PIPELINE",
  "created_at": 1710000000000
}
```

### 4.2 查询状态

`GET /api/v1/orchestrator/reviews/{task_id}`

成功响应（200）：

```json
{
  "task_id": "4b2f2e95-16f4-4bca-8b74-e4c019c16bb7",
  "status": "RUNNING",
  "error": null,
  "started_at": 1710000001000,
  "completed_at": null
}
```

### 4.3 查询结果

`GET /api/v1/orchestrator/reviews/{task_id}/result`

响应语义：
1. `200`：`COMPLETED`，返回完整结果
2. `202`：`PENDING/RUNNING`，返回最小结果（含状态）
3. `500`：`FAILED`，返回失败信息
4. `404`：任务或结果不存在

成功响应（200）：

```json
{
  "task_id": "4b2f2e95-16f4-4bca-8b74-e4c019c16bb7",
  "status": "completed",
  "has_critical_flag": false,
  "issues": [
    {
      "severity": "WARNING",
      "file": "src/A.java",
      "line": 42,
      "type": "sql_injection",
      "message": "...",
      "suggestion": "..."
    }
  ],
  "total_tokens_used": 1234,
  "review_duration_ms": 2045,
  "summary": "",
  "error": null
}
```

错误响应（统一结构）：

```json
{
  "success": false,
  "code": "INVALID_REQUEST",
  "message": "diff_entries is required and cannot be empty",
  "trace_id": "e9be2c0d-b151-48af-9543-2e4048ae7fbe",
  "timestamp": 1710000000000
}
```

## 5. 异步 MQ 契约（Orchestrator <-> Agent）

## 5.1 Broker 拓扑

1. Exchange：`review.exchange`（topic）
2. DLX：`review.dlx`（direct）
3. DLQ：`review.dlq`
4. Result Queue：`review.result.queue`
5. 队列 TTL：Task 默认 10 分钟，Result 默认 60 分钟
6. 队列绑定规范（避免重复消费）：
   - `review.pipeline.queue` 仅绑定 `review.pipeline.task`
   - `review.agent.queue` 仅绑定 `review.multi_agent.task`（迁移期可同时绑定 `review.agent.task`）
   - `review.simple.queue` 仅绑定 `review.simple.task`

## 5.2 Task 事件（Orchestrator -> Agent）

routing key：
1. `review.pipeline.task`
2. `review.multi_agent.task`
3. `review.simple.task`
4. 兼容旧消费者可额外接收 `review.agent.task`（迁移期）

消息属性（建议）：
1. `message_id = task_id`
2. `content_type = application/json`
3. `delivery_mode = 2`（持久化）
4. `priority`：hotfix=9，默认=5

消息体：

```json
{
  "task_id": "4b2f2e95-16f4-4bca-8b74-e4c019c16bb7",
  "request_id": "4b2f2e95-16f4-4bca-8b74-e4c019c16bb7",
  "mode": "PIPELINE | MULTI_AGENT | SIMPLE",
  "project_dir": "C:/repo",
  "tool_server_url": "http://localhost:9090",
  "created_at": 1710000000000,
  "diff_entries": [
    {
      "file_path": "src/A.java",
      "content": "@@ ...",
      "token_count": 123
    }
  ],
  "llm_config": {
    "provider": "openai | claude",
    "model": "gpt-4o",
    "api_key_env": "DIFFGUARD_API_KEY",
    "base_url": "https://api.openai.com/v1",
    "max_tokens": 16384,
    "temperature": 0.3,
    "timeout_seconds": 300
  },
  "review_config": {
    "language": "zh",
    "rules_enabled": ["security", "bug-risk", "code-style", "performance"]
  },
  "allowed_files": ["src/A.java"]
}
```

兼容说明：
1. Agent 侧当前 `ReviewMode` 仅定义 `PIPELINE/MULTI_AGENT`
2. `SIMPLE` 任务默认不投递 Agent，可由 Orchestrator 本地执行（推荐）

## 5.3 Result 事件（Agent -> Orchestrator）

routing key：
1. `review.result.completed`
2. `review.result.failed`

消息体：

```json
{
  "task_id": "4b2f2e95-16f4-4bca-8b74-e4c019c16bb7",
  "request_id": "4b2f2e95-16f4-4bca-8b74-e4c019c16bb7",
  "status": "completed | failed",
  "has_critical_flag": false,
  "issues": [],
  "total_tokens_used": 1000,
  "review_duration_ms": 3000,
  "summary": "",
  "error": null
}
```

关联规则：
1. 优先使用 `task_id` 关联任务
2. 缺失 `task_id` 时回退 `request_id`
3. 两者均缺失时 `nack(requeue=false)`，进入 DLQ

## 6. 错误码与重试策略

HTTP 错误码：
1. `400 INVALID_REQUEST`：字段缺失/非法
2. `401 UNAUTHORIZED`：内部调用鉴权失败
3. `404 TASK_NOT_FOUND | RESULT_NOT_FOUND`
4. `409 IDEMPOTENCY_CONFLICT`
5. `422 MISSING_TOOL_SERVER_URL` 等可校验配置错误
6. `500 INTERNAL_ERROR`：服务内部异常
7. `503 DOWNSTREAM_UNAVAILABLE`：Agent/MQ/DB 不可用

MQ 消费策略：
1. 结果处理成功 `ack`
2. 不可恢复异常 `nack(requeue=false)` -> DLQ
3. DLQ 重放前必须检查 `task_id` 最终状态，避免重复写入结果

## 7. 版本化与兼容约束

1. 契约版本：`v1`（路径版本 `api/v1`）
2. 向后兼容保留：`task_id/request_id` 双字段
3. 新增字段仅可追加，不得删除既有字段或改变语义
4. 密钥策略：仅允许 `api_key_env`，禁止明文 `api_key`
