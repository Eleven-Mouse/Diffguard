# DiffGuard 目标架构（To-Be）

更新时间：2026-05-23

## 1. 目标原则

1. 接入层、编排层、工具层明确分离
2. 协议先稳定，内部实现可迭代替换
3. 每阶段都可回滚，不做一次性大爆炸迁移

## 2. 目标服务划分（Java 侧）

## 2.1 gateway-service

职责：

1. CLI 接入
2. Webhook 接入（验签、限流、事件过滤）
3. 对 orchestrator 的调用适配（本地 fallback 可配置）

不负责：

1. 具体审查编排执行
2. Tool API 执行

## 2.2 review-orchestrator-service

职责：

1. 任务状态机管理
2. diff + AST + rules + review 引擎执行
3. 同步 API 与 MQ 异步桥接
4. 结果聚合与查询

契约：见 `ORCHESTRATOR_CONTRACT.md`

## 2.3 tool-service

职责：

1. 会话管理（`X-Session-Id`）
2. `/api/v1/tools/*` 工具执行
3. 文件访问沙箱

要求：协议与 Python `tool_client.py` 保持兼容。

## 2.4 python-agent-service

保持现有职责不变：

1. `PIPELINE` / `MULTI_AGENT` 执行
2. Tool 调用
3.（可选）MQ worker

## 3. 目标调用链

## 3.1 同步链路

```text
gateway-service
  -> review-orchestrator-service (/api/v1/orchestrator/reviews)
      -> python-agent-service (/api/v1/review)
          -> tool-service (/api/v1/tools/*)
```

## 3.2 异步链路

```text
gateway-service
  -> review-orchestrator-service (submit task)
      -> RabbitMQ review.exchange
          -> python-agent worker
              -> RabbitMQ review.result.*
      -> orchestrator consume result -> persist/query
```

## 4. 目标配置分层

统一原则：

1. 配置文件仅存“变量名与非敏感参数”
2. 敏感值只从环境变量读取
3. 服务间 URL 显式配置，不隐式拼接

关键配置：

1. `DIFFGUARD_AGENT_URL`
2. `DIFFGUARD_TOOL_SERVER_URL`
3. `DIFFGUARD_ORCHESTRATOR_URL`（新增）
4. `RABBITMQ_*`

## 5. 目标演进路径（高层）
1. 完成 Orchestrator 契约 + 骨架（已完成）
2. gateway 接入 orchestrator 远程调用（已完成）
3. 下沉旧 `ReviewOrchestrator` 到新服务并清理重复逻辑（已完成）
4. 完成回归与 fallback 开关验证（已完成）
5. 收敛 Python 双目录结构（`app` / `diffguard`）**
