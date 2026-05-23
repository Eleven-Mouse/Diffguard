# DiffGuard 重构迁移计划（可执行版）

更新时间：2026-05-23  
周期：第一轮 1 周（5 个工作日）

## 1. 重构策略

采用“绞杀者模式”：

1. 新链路先旁路上线
2. 通过配置开关逐步切流
3. 稳定后下线旧路径

不采用：

1. 一次性替换全部编排逻辑
2. 未验证就删除旧实现

## 2. 范围定义（第一轮）

## 2.1 In Scope

1. `services/gateway`：
   - gateway -> orchestrator 远程适配
   - 保留本地 fallback
2. `services/gateway`：
   - orchestrator API 骨架补齐 DTO/错误码/幂等
3. 文档与进度同步更新

## 2.2 Out of Scope

1. Python 业务逻辑大改
2. RAG 外部依赖替换（仅方案评估，不在本轮实施）
3. 结果服务独立拆分

## 3. 一周执行清单

## Day 1：基线固化

1. 固化现状文档（已完成）：
   - `ARCHITECTURE_CURRENT.md`
   - `ARCHITECTURE_TARGET.md`
   - `ORCHESTRATOR_CONTRACT.md`
2. 建立回归最小集（手工或自动）：
   - `review --staged`
   - `review --staged --pipeline`
   - `review --staged --multi-agent`

交付物：

1. 基线文档齐全
2. 最小回归脚本或命令清单

## Day 2：gateway 远程适配层

1. 新增 `OrchestratorClient`（HTTP）
2. 在 `ReviewApplicationService` 增加远程执行分支
3. 加入开关：
   - `orchestrator.mode=legacy|remote`
   - `orchestrator.url=http://...`

交付物：

1. 本地可切换 legacy/remote
2. remote 失败自动 fallback 到 legacy（可配置）

## Day 3：orchestrator API 对齐

1. 补齐请求/响应 DTO
2. 实现统一错误码映射（400/409/422/500/503）
3. 增加幂等键处理（`X-Idempotency-Key`）

交付物：

1. API 行为与 `ORCHESTRATOR_CONTRACT.md` 对齐
2. Postman 或 curl 示例可跑通

## Day 4：异步路径打通（最小闭环）

1. 校准 routing key 与消息体字段
2. 结果关联统一（`task_id` + `request_id` 兼容）
3. DLQ 失败路径可观测

交付物：

1. 一条异步任务从提交到结果查询可跑通

## Day 5：收尾与验收

1. 文档收敛（README / PROGRESS）
2. 验证脚本执行并记录结果
3. 提交风险清单与下一轮计划

交付物：

1. 第一轮重构验收记录
2. 第二轮待办（orchestrator 深度迁移）

## 4. 风险控制

1. 双写风险：禁止 gateway 与 orchestrator 同时写同一任务状态（需明确 owner）
2. 协议漂移：禁止未更新契约文档就改字段
3. 回滚失败：每个阶段保留独立开关与旧路径
4. 测试噪音：历史失败测试与本轮变更隔离评估

## 5. 验收标准（第一轮）

1. gateway 可切到 remote orchestrator，且可 fallback
2. orchestrator 三个核心 API 可用：
   - 创建任务
   - 查询状态
   - 查询结果
3. `pipeline`、`multi-agent` 至少各验证一次
4. `PROGRESS.md` 按约束完成状态迁移更新

