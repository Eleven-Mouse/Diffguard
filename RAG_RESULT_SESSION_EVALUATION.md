# Tool Session / Result-Service / External RAG 评估与方案

更新时间：2026-05-23
范围：任务 1-4（架构评估与可执行落地方案）

## 1. 结论摘要

1. Tool Session 多实例一致性（不依赖 Redis）建议采用：
   - 短期：`session_id` 一致性哈希路由 + 实例本地会话内存
   - 中期：增加 DB 会话注册表（仅元数据）+ owner lease 机制
   - 长期：会话“可重建”，将重型索引变为 project 级缓存资产
2. `result-service` 的数据所有权应从 `review-orchestrator-service` 明确切分：
   - orchestrator 负责“任务状态机 + 结果写入”
   - result-service 负责“结果查询/检索/审计投影”，禁止反向改状态
3. 外部 RAG（`rag-anything`）建议以“侧车服务 + 适配器”接入，保持 Tool 协议不变：
   - 保持 `POST /api/v1/tools/semantic-search`、`X-Session-Id` 不变
   - 在 Java 侧仅替换 `SemanticSearchTool` 内部检索器实现
4. A/B 对比结论（架构级）：
   - 纯代码检索：现有 `domain/coderag` 预期更稳、更低成本
   - 多模态文档（图表/公式/Office/PDF）：`rag-anything` 覆盖能力明显更强
   - 推荐“双通道”策略：默认 `coderag`，按仓库/文件类型切换 `rag-anything`

---

## 2. 现状约束（基于当前代码）

1. Tool Session 当前为进程内内存态，TTL 10 分钟；会话含 `projectDir/diffEntries/allowedFiles`，并在 session 内挂载重型工具资源（调用图、语义检索）。
2. Tool 调用协议已稳定：Python 侧统一走 `X-Session-Id` + `/api/v1/tools/*`。
3. Orchestrator 当前任务状态与结果仍以内存 `ConcurrentHashMap` 为主（`tasks/results/idempotencyIndex`），结果可通过 HTTP 查询和 MQ 回传聚合。
4. 现有 `CodeRAGService` 已支持 `EmbeddingProvider + VectorStore` 抽象，默认可走 TF-IDF/OpenAI embedding，向量存储可走 Chroma（不可达时回退进程内存）。

---

## 3. 任务 1：Tool Session 多实例一致性（无 Redis）

## 3.1 目标问题

在多实例 `tool-service` 下，`X-Session-Id` 请求可能落到非 owner 实例，导致“会话不存在/过期”与重型资源重复构建。

## 3.2 方案对比

### 方案 A：L7 粘性会话（按 `X-Session-Id` hash）

优点：
1. 改动小，最快可上线
2. 不引入外部状态依赖

缺点：
1. owner 实例宕机时会话全失
2. 无跨实例恢复能力

适用：单可用区、小规模、先稳住协议阶段。

### 方案 B：DB 会话注册表（推荐）

核心：
1. 本地内存仍保存热会话与重型对象
2. DB 仅保存会话元数据（`session_id/project_dir/diff_digest/allowed_files/owner_instance/lease_until`）
3. 非 owner 实例收到请求时可：
   - 302/内部代理到 owner
   - owner 不可用时触发“重建会话”

优点：
1. 不依赖 Redis，仍可实现跨实例一致性
2. 支持宕机转移与审计

缺点：
1. 增加 DB 读写与 lease 复杂度
2. 需要控制重建风暴

适用：生产多实例。

### 方案 C：全无状态（每次请求重建上下文）

优点：
1. 一致性最简单

缺点：
1. 调用图与语义索引构建成本过高
2. 延迟不可接受

结论：不建议。

## 3.3 推荐落地顺序

1. 第一步：接入层按 `X-Session-Id` 一致性哈希路由（A）。
2. 第二步：增加 `tool_session_registry` 表 + lease（B）。
3. 第三步：会话“可重建化”，将 project 级重型索引做实例本地缓存 + 版本戳。

---

## 4. 任务 2：`result-service` 数据所有权边界

## 4.1 聚合边界建议

`review-orchestrator-service`（写模型 Owner）：
1. `ReviewTask`：状态机（PENDING/RUNNING/COMPLETED/FAILED）
2. `ReviewResult`：问题明细、摘要、token、耗时
3. 幂等索引、MQ 回传去重

`result-service`（读模型 Owner）：
1. 查询 API（按 repo/PR/时间/状态/严重度）
2. 搜索与统计投影（列表、趋势、审计视图）
3. 导出与归档

## 4.2 写入规则（必须）

1. task 状态只允许 orchestrator 写入。
2. result-service 不直接修改 task 状态，只消费事件更新投影。
3. 事件模型建议：
   - `TaskCreated`
   - `TaskStatusChanged`
   - `TaskCompleted`
   - `TaskFailed`

## 4.3 反模式（避免）

1. gateway 直接写 result 表
2. result-service 回写 orchestrator 主表
3. 无版本号的“最后写入覆盖”

---

## 5. 任务 3：外部 RAG（`rag-anything`）适配方案

## 5.1 适配原则

1. 不改 Python `tool_client.py` 协议，不改 `/api/v1/tools/semantic-search`。
2. 仅在 Java Tool 内部替换语义检索实现。
3. 保留现有 `CodeRAGService` 作为默认与降级路径。

## 5.2 建议架构

```text
Python Agent
  -> Java Tool API (/api/v1/tools/semantic-search)
      -> SemanticSearchTool
          -> SemanticRetrieverAdapter (interface)
              -> CodeRagAdapter (current)
              -> RagAnythingAdapter (HTTP sidecar)
```

## 5.3 最小接口（建议）

1. `indexProject(projectDir, metadata)`
2. `search(query, topK, sessionContext)`
3. `searchRelated(diffFilePath, diffContent, topK)`
4. `health()`

## 5.4 配置开关（建议新增）

1. `tool.semantic_retriever: coderag | rag_anything | auto`
2. `rag_anything.url`
3. `rag_anything.timeout_seconds`
4. `rag_anything.fail_open_to_coderag: true`

## 5.5 风险

1. `rag-anything` 依赖链较重（MinerU、可选 PaddleOCR、Office 解析依赖 LibreOffice），部署复杂度显著高于当前 `coderag`。
2. 多模态 OCR/解析链路延迟抖动更高，需异步化或预索引策略。

---

## 6. 任务 4：外部 RAG vs 现有 `domain/coderag` A/B 对比

## 6.1 对比口径

维度：
1. 召回质量：Recall@K、MRR、nDCG
2. 延迟：P50/P95（检索端到端）
3. Token 成本：千次查询 token 与推理费用
4. 稳定性：失败率、超时率、降级成功率

## 6.2 数据集分层（建议）

1. Code-only PR：Java 代码 diff + 调用链上下文
2. Mixed PR：代码 + Markdown 设计文档
3. Multimodal Docs：PDF/Office/图表/公式（知识问答）

## 6.3 评估结论（架构级）

| 维度 | A: 当前 `domain/coderag` | B: `rag-anything` |
|---|---|---|
| 代码检索相关性 | 高（面向代码切片优化） | 中-高（依赖外部解析质量） |
| 多模态文档覆盖 | 低（核心是代码语义） | 高（原生面向文本+图像+表格+公式） |
| 延迟 | 低-中（本地切片 + Chroma/内存） | 中-高（文档解析与多模态流水线） |
| 成本 | 低（TF-IDF 可零 token）到中（OpenAI embedding） | 中-高（多模型 + 解析链） |
| 稳定性 | 高（依赖少，已有降级） | 中（依赖面大，外部组件更多） |
| 运维复杂度 | 低 | 高 |

**推荐策略：**
1. 默认 A（`coderag`）处理代码审查主链。
2. 对“含多模态文档”的仓库或特定查询路由 B（`rag-anything`）。
3. B 失败自动回退 A，保证 Tool 协议与主链稳定。

## 6.4 结果解释（推断）

1. A 在当前任务（代码审查）里优势来自更低耦合和更短链路。
2. B 的价值主要在“非纯代码知识”召回（架构图、表格、公式、Office/PDF）。
3. 因此不建议“一刀切替换”，应做 `auto` 路由。

---

## 7. 推荐执行计划（两周）

## Week 1

1. Tool Session：完成方案 A（hash 路由）设计与网关配置。
2. `result-service`：冻结所有权与事件模型草案。
3. `SemanticRetrieverAdapter` 抽象接口 + `CodeRagAdapter` 落地。

## Week 2

1. `RagAnythingAdapter`（sidecar HTTP）打通 + fail-open 回退。
2. 跑首轮 A/B（至少 Code-only + Multimodal 两类数据）。
3. 输出 go/no-go 决策：是否扩大 B 路由比例。

---

## 8. 外部资料（用于本次评估）

1. RAG-Anything 论文（arXiv）：https://arxiv.org/abs/2510.12323
2. RAG-Anything 仓库与安装说明（LightRAG、MinerU、LibreOffice 依赖）：https://github.com/HKUDS/RAG-Anything
3. RAG-Anything `requirements.txt`（`lightrag-hku`、`mineru[core]`）：https://raw.githubusercontent.com/HKUDS/RAG-Anything/main/requirements.txt
4. RAG-Anything 许可证（MIT）：https://raw.githubusercontent.com/HKUDS/RAG-Anything/main/LICENSE
5. LightRAG 仓库（与 RAG-Anything 集成、RAGAS 评估能力说明）：https://github.com/HKUDS/LightRAG

