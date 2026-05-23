# Orchestrator DTO + Python Schema 对齐改造清单

更新时间：2026-05-23

## 1. 对齐目标

- 以 `ORCHESTRATOR_CONTRACT.md` 为准，对齐 Java Orchestrator DTO 与 Python Agent Schema。
- 保持 `task_id/request_id` 双字段兼容，支持渐进迁移。

## 2. 已完成（本次改造）

- [x] Python `ReviewMode` 增加 `SIMPLE` 枚举值（兼容契约模式全集）。
  - 文件：`services/agent/diffguard/models/schemas.py`
- [x] Python `ReviewRequest` 增加 `task_id`，并与 `request_id` 做双向补齐。
  - 文件：`services/agent/diffguard/models/schemas.py`
- [x] Python `ReviewResponse` 增加 `task_id`，默认从 `request_id` 回填。
  - 文件：`services/agent/diffguard/models/schemas.py`
- [x] Python `LlmConfig.api_key` 改为序列化排除，避免明文透传风险。
  - 文件：`services/agent/diffguard/models/schemas.py`
- [x] MQ Consumer 结果发布前显式补齐 `task_id/request_id`。
  - 文件：`services/agent/diffguard/messaging/rabbitmq_consumer.py`
- [x] MQ Consumer 任务关联改为 `task_id` 优先、`request_id` 回退。
  - 文件：`services/agent/diffguard/messaging/rabbitmq_consumer.py`
- [x] MQ Consumer 对不支持模式（如 `SIMPLE`）返回失败结果，不再静默降级到 `MULTI_AGENT`。
  - 文件：`services/agent/diffguard/messaging/rabbitmq_consumer.py`
- [x] MQ Queue 绑定改为精确 routing key，避免 `review.*.task` 导致重复消费。
  - 文件：`services/agent/diffguard/messaging/rabbitmq_consumer.py`
- [x] HTTP Review 接口返回结果补齐 `task_id`。
  - 文件：`services/agent/diffguard/main.py`
- [x] Java Orchestrator DTO 增加 `@JsonIgnoreProperties(ignoreUnknown = true)`，支持契约追加字段兼容。
  - 文件：
    - `services/gateway/src/main/java/com/diffguard/adapter/orchestrator/dto/ReviewTaskCreateRequest.java`
    - `services/gateway/src/main/java/com/diffguard/adapter/orchestrator/dto/DiffEntryDto.java`
    - `services/gateway/src/main/java/com/diffguard/adapter/orchestrator/dto/ReviewTaskCreateResponse.java`
    - `services/gateway/src/main/java/com/diffguard/adapter/orchestrator/dto/ReviewTaskStatusResponse.java`
    - `services/gateway/src/main/java/com/diffguard/adapter/orchestrator/dto/ReviewTaskResultResponse.java`
    - `services/gateway/src/main/java/com/diffguard/adapter/orchestrator/dto/IssueResponseDto.java`

## 3. 待完成（建议下一批）

- [ ] Java 侧补充 Orchestrator DTO 单测（反序列化兼容新增字段、必填字段校验）。
- [ ] Python 侧补充 Schema/Consumer 单测（`task_id/request_id` 回填、`SIMPLE` 失败分支、routing 绑定行为）。
- [ ] 按契约完成真实 MQ 验收证据沉淀（成功链路、失败链路、DLQ 截图/日志）。

