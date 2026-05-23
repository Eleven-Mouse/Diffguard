# DiffGuard 第一轮重构验收记录（Day 5）

更新时间：2026-05-23  
范围：`services/gateway` 微服务化第一轮（Day1~Day5）

## 1. 验收目标

1. Gateway 可切换到 remote orchestrator，失败可 fallback。  
2. Orchestrator 三个核心 API 可用：创建任务、查询状态、查询结果。  
3. MQ 异步链路具备最小闭环能力，且 `task_id/request_id` 兼容。  
4. 文档与进度同步收敛，形成下一轮输入。

## 2. 本轮已完成（代码与文档）

1. `tool-server` 独立启动能力与配置接入已完成。  
2. `orchestrator-server` 独立启动能力与基础 API 骨架已完成。  
3. gateway -> orchestrator 远程调用能力已完成（含 legacy fallback 开关）。  
4. Day3 接口收敛已完成：DTO 化、统一错误响应、幂等键冲突处理。  
5. Day4 MQ 闭环代码已完成：
   - Orchestrator 对 `PIPELINE/MULTI_AGENT` 任务进行 MQ 发布
   - Orchestrator 消费 `review.result.*` 并回写任务状态/结果
   - 结果关联优先 `task_id`，回退 `request_id`
   - 消费异常 `Nack(requeue=false)` 进入 DLQ
   - MQ 不可用自动降级回本地同步执行
6. Java 编译验证通过：
   - 命令：`mvn -DskipTests compile`（目录：`services/gateway`）
   - 结果：`BUILD SUCCESS`

## 3. 本轮未完成（需下一步联调）

1. RabbitMQ + Python Agent 实际环境下的端到端联调记录未沉淀。  
2. 失败注入场景（agent 报错、无效结果消息）与 DLQ 观测结果未留档。  
3. remote/legacy 切换回归测试样例仍需补充。

## 4. 结论

本轮达到“代码闭环可用 + 可编译 + 可降级”目标；  
尚未达到“端到端联调验收完成”目标。  
建议将“真实 MQ 联调与失败路径验收”作为下一轮 P0。

## 5. 下一轮执行清单（P0）

1. 跑通一条 `PIPELINE` MQ 任务（提交 -> 消费 -> 结果查询）。  
2. 跑通一条 `MULTI_AGENT` MQ 任务（提交 -> 消费 -> 结果查询）。  
3. 注入一条失败任务并验证 `FAILED` 状态与 DLQ 观测。  
4. 将联调证据（命令、关键日志、状态截图或结果 JSON）补充到本文件。
