# DiffGuard AGENT.md — AI Agent 协作规范

> 本文档的目标读者是 AI Agent（如 Claude Code、Cursor、Copilot）。阅读本文档后，AI 应能在 5 分钟内理解项目架构，15 分钟内定位核心代码，30 分钟内安全修改代码。
> 所有涉及本项目代码的修改行为必须遵守本文档。违反本文档的代码应在 Code Review 阶段被拒绝。

---

## 1. 项目简介

**DiffGuard** 是 AI 驱动的多维度智能代码审查系统。它不是一个简单的 "把 diff 扔给 LLM" 的工具——它通过 Java Gateway 提供的深度代码理解能力（AST 分析、代码知识图谱、语义搜索），结合 Python Agent 的 LLM Pipeline，实现上下文感知的生产级代码审查。

### 核心价值

| 问题 | DiffGuard 解决方案 |
|------|-------------------|
| LLM 审查缺乏项目上下文 | Java Gateway 提供 AST + CodeGraph + CodeRAG，Agent 通过 Tool 调用获取 |
| 误报率高 | 两阶段过滤：正则硬规则（零 LLM 成本）+ 可选 LLM 验证 |
| 每次审查 LLM 成本高 | 静态规则预过滤 + diff 摘要阶段减少 token 消耗 |
| 集成困难 | CLI（Git Hook）/ Server（GitHub Webhook）/ CI（GitHub Action）三种部署模式 |

### 核心功能

- 4 阶段 Pipeline 审查：Summary → 并行 Review（Security/Logic/Quality）→ Aggregation → False Positive Filter
- 6 种 Agent Tool：文件内容、diff 上下文、方法定义、调用图、关联文件、语义搜索
- 大 PR 自动分块：超过 10 个文件或 60000 字符时自动拆分为多个 chunk 独立审查
- Diff 行号映射：将 LLM 输出的 diff 上下文行号转换为实际文件行号，确保 GitHub 评论落点准确

---

## 2. 技术架构

### 2.1 双服务架构

```
┌─────────────────────────────────────────────────────────────┐
│                    DiffGuard Gateway (Java 21)               │
│                    Javalin + JavaParser + JGit               │
│                                                              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐  │
│  │   Webhook    │    │   Tool       │    │  AST/Code    │  │
│  │   Server     │    │   Server     │    │  Graph/RAG   │  │
│  │   :8080      │    │   :9090      │    │  Engine      │  │
│  └──────┬───────┘    └──────┬───────┘    └──────────────┘  │
│         │                   │                               │
│  POST /webhook/github  POST /api/v1/tools/*                │
└─────────┼───────────────────┼───────────────────────────────┘
          │                   │
          │ HTTP POST         │ HTTP POST
          │ /api/v1/          │ /api/v1/tools/*
          │ webhook-review    │
          ▼                   │
┌─────────────────────────────▼───────────────────────────────┐
│                   DiffGuard Agent (Python 3.12)              │
│                   FastAPI + LangChain + httpx                 │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │              PipelineOrchestrator                      │  │
│  │  SummaryStage → ReviewerStage → AggregationStage     │  │
│  │                   → FalsePositiveFilterStage          │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                              │
│  Agent :8000 (FastAPI)                                       │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 技术栈

| 层 | Java Gateway | Python Agent |
|----|-------------|-------------|
| 语言 | Java 21 | Python 3.12 |
| Web 框架 | Javalin 5.6 | FastAPI + Uvicorn |
| LLM | — | LangChain 0.3 (ChatAnthropic / ChatOpenAI) |
| AST | JavaParser 3.26 | — |
| Git | JGit 6.8 | httpx (GitHub REST API) |
| JSON | Jackson 2.17 | Pydantic 2 |
| Cache | Caffeine | — |
| 构建 | Maven (shade plugin → fat jar) | uv + hatch |
| 测试 | JUnit 5 + Mockito | pytest + pytest-asyncio |

### 2.3 两种运行模式

| 模式 | 入口 | 需要 Java | Tool Server | 适用场景 |
|------|------|-----------|-------------|---------|
| **GitHub Action** | `python -m app.github_action_runner` | 否 | 不可用 | CI/CD 集成 |
| **Webhook Server** | `java -jar diffguard.jar` + Python Agent | 是 | 可用 | 自托管服务 |

---

## 3. 项目结构（核心目录）

```
DiffGuard/
├── services/
│   ├── gateway/                        # Java Gateway 服务
│   │   ├── pom.xml                     # Maven 构建，Java 21
│   │   ├── config/application.yml      # 运行时配置
│   │   ├── Dockerfile
│   │   └── src/main/java/com/diffguard/
│   │       ├── DiffGuard.java          # ★ Java 入口（main method）
│   │       ├── adapter/
│   │       │   ├── webhook/            # Webhook 接收 + 签名验证 + 限流
│   │       │   │   ├── WebhookServer.java         # ★ Javalin 服务器，启动两个端口
│   │       │   │   ├── WebhookController.java      # ★ Webhook 请求处理器
│   │       │   │   ├── WebhookDispatcher.java      # ★ 异步转发到 Python Agent
│   │       │   │   ├── GitHubPayloadParser.java    # PR payload 解析
│   │       │   │   ├── SignatureVerifier.java      # HMAC-SHA256 签名验证
│   │       │   │   ├── RateLimiter.java            # IP 级限流
│   │       │   │   └── GitHubApiClient.java        # GitHub API 调用
│   │       │   └── toolserver/         # Tool Server（给 Python Agent 调用）
│   │       │       ├── ToolServerController.java   # ★ Tool 端点路由 + 鉴权
│   │       │       ├── ToolSessionManager.java     # ★ Session 管理（10min TTL）
│   │       │       └── model/DiffFileEntry.java
│   │       ├── domain/
│   │       │   ├── agent/              # Agent 相关领域对象
│   │       │   │   ├── core/           # AgentContext, AgentTool, ToolResult
│   │       │   │   ├── python/         # PythonAgentClient（Java→Python HTTP 客户端）
│   │       │   │   └── tools/          # ★ 6 个 Tool 实现
│   │       │   │       ├── ToolRegistry.java
│   │       │   │       ├── FileAccessSandbox.java  # 路径穿越防护
│   │       │   │       ├── GetFileContentTool.java
│   │       │   │       ├── GetDiffContextTool.java
│   │       │   │       ├── GetMethodDefinitionTool.java
│   │       │   │       ├── GetCallGraphTool.java
│   │       │   │       ├── GetRelatedFilesTool.java
│   │       │   │       └── SemanticSearchTool.java
│   │       │   ├── ast/                # ★ AST 分析引擎
│   │       │   │   ├── ASTAnalyzer.java           # 单文件 AST 分析
│   │       │   │   ├── ASTCache.java              # Caffeine 内容哈希缓存
│   │       │   │   ├── ASTContextBuilder.java
│   │       │   │   ├── ASTEnricher.java
│   │       │   │   ├── ProjectASTAnalyzer.java    # ★ 全项目 AST 扫描
│   │       │   │   ├── spi/                       # 语言 SPI 扩展点
│   │       │   │   │   ├── LanguageASTProvider.java
│   │       │   │   │   ├── JavaASTProvider.java
│   │       │   │   │   └── ASTProviderRegistry.java
│   │       │   │   └── model/                    # AST 数据模型
│   │       │   │       ├── ASTAnalysisResult.java
│   │       │   │       ├── ClassInfo.java
│   │       │   │       ├── MethodInfo.java
│   │       │   │       ├── CallEdge.java
│   │       │   │       ├── ResolvedCallEdge.java
│   │       │   │       └── ... (ControlFlowNode, DataFlowNode, FieldAccessInfo)
│   │       │   ├── codegraph/          # ★ 代码知识图谱
│   │       │   │   ├── CodeGraph.java             # 图数据结构（节点+边）
│   │       │   │   ├── CodeGraphBuilder.java      # ★ 从 AST 结果构建图
│   │       │   │   ├── GraphNode.java             # FILE/CLASS/INTERFACE/METHOD
│   │       │   │   └── GraphEdge.java             # CALLS/EXTENDS/IMPLEMENTS/IMPORTS/CONTAINS
│   │       │   └── coderag/            # ★ Code RAG 语义搜索
│   │       │       ├── CodeRAGService.java        # RAG 门面（索引+检索）
│   │       │       ├── CodeSlicer.java            # 多粒度代码切片
│   │       │       ├── CodeChunk.java             # 切片数据模型
│   │       │       ├── EmbeddingProvider.java      # 向量化接口
│   │       │       ├── LocalTFIDFProvider.java     # TF-IDF（零依赖）
│   │       │       ├── OpenAiEmbeddingProvider.java # OpenAI Embedding
│   │       │       ├── VectorStore.java           # 向量存储接口
│   │       │       └── InMemoryVectorStore.java    # 内存向量存储
│   │       ├── infrastructure/
│   │       │   ├── config/             # 配置加载
│   │       │   │   ├── ConfigLoader.java          # ★ 三层配置加载（项目→用户→内置）
│   │       │   │   └── ReviewConfig.java          # ★ 配置数据模型
│   │       │   ├── common/             # JacksonMapper, TokenEstimator
│   │       │   └── git/                # DiffCollector (JGit)
│   │       └── exception/              # DiffGuardException, WebhookException
│   │
│   └── agent/                          # Python Agent 服务
│       ├── pyproject.toml              # Python 依赖定义
│       ├── Dockerfile
│       ├── config/
│       │   └── false_positive_rules.yaml  # ★ 误报过滤规则库
│       ├── requirements-github-action.txt  # GitHub Action 精简依赖
│       ├── app/
│       │   ├── main.py                 # ★ FastAPI 入口（/api/v1/review, /api/v1/webhook-review）
│       │   ├── config.py               # Python 侧 Settings（环境变量）
│       │   ├── metrics.py              # ReviewMetrics（可观测性）
│       │   ├── github_action_runner.py # ★ GitHub Action 入口
│       │   ├── github_api.py           # 同步 GitHub 客户端（Action 模式用）
│       │   ├── agent/
│       │   │   ├── pipeline_orchestrator.py  # ★ Pipeline 编排器（分块+去重）
│       │   │   ├── llm_utils.py        # ★ LLM 工厂 + Prompt 加载 + 重试
│       │   │   ├── false_positive_filter.py  # ★ 两阶段误报过滤器
│       │   │   ├── diff_parser.py      # ★ Diff 行号映射器
│       │   │   ├── base.py             # Agent 基类（预留）
│       │   │   └── pipeline/
│       │   │       ├── pipeline_config.py    # YAML 驱动的 Pipeline DSL
│       │   │       ├── pipeline-config.yaml  # Pipeline 配置文件
│       │   │       └── stages/
│       │   │           ├── base.py           # ★ PipelineContext + PipelineStage 抽象
│       │   │           ├── summary.py        # ★ Stage 1: 变更摘要 + 文件路由
│       │   │           ├── reviewer.py       # ★ Stage 2: 并行多维度审查
│       │   │           ├── aggregation.py    # ★ Stage 3: 聚合去重 + 行号映射
│       │   │           ├── false_positive_filter.py  # ★ Stage 4: 误报过滤
│       │   │           └── static_rules.py   # 静态规则检查（预留）
│       │   ├── llm/prompts/            # ★ Prompt 模板目录
│       │   │   ├── pipeline/           # Pipeline 各阶段 prompt
│       │   │   │   ├── diff-summary-system.txt
│       │   │   │   ├── diff-summary-user.txt
│       │   │   │   ├── security-system.txt
│       │   │   │   ├── security-user.txt
│       │   │   │   ├── logic-system.txt
│       │   │   │   ├── logic-user.txt
│       │   │   │   ├── quality-system.txt
│       │   │   │   ├── quality-user.txt
│       │   │   │   ├── aggregation-system.txt
│       │   │   │   └── aggregation-user.txt
│       │   │   ├── reviewagents/       # 预留扩展
│       │   │   └── react-user.txt      # ReAct Agent prompt
│       │   ├── github/
│       │   │   └── client.py           # ★ 异步 GitHub API 客户端
│       │   ├── models/
│       │   │   └── schemas.py          # ★ Pydantic 数据模型（API schema）
│       │   └── tools/
│       │       ├── definitions.py      # ★ LangChain @tool 定义（6 个工具）
│       │       └── tool_client.py      # ★ Java Tool Server HTTP 客户端
│       └── tests/                      # 测试目录
│           ├── conftest.py
│           ├── test_pipeline_stages.py
│           ├── test_false_positive_filter.py
│           ├── test_diff_parser.py
│           └── ...
│
├── shared/config/
│   └── review-config-template.yml      # 内置默认配置模板
├── action.yml                          # ★ GitHub Action 定义
├── docker-compose.yml                  # 双服务 Docker 部署
├── .github/workflows/ci.yml            # CI 流水线
├── AGENT.md                            # 本文件
├── JAVA.md                             # Java 代码规范
├── PYTHON.md                           # Python 代码规范
└── README.md                           # 项目文档
```

---

## 4. 核心调用链

### 4.1 Webhook 模式（完整链路）

```
GitHub PR Event
    │
    ▼
WebhookServer (:8080)           ← DiffGuard.java → ConfigLoader → WebhookServer
    │
    ▼
WebhookController               ← 验证签名 → 限流 → 解析 payload → 过滤 action
    │
    ▼
WebhookDispatcher               ← Virtual Thread 异步转发
    │
    ▼
Python Agent (:8000)            ← POST /api/v1/webhook-review
    │
    ▼
AsyncGitHubClient               ← 获取 PR diff + 历史审查评论
    │
    ▼
PipelineOrchestrator            ← 判断是否需要分块
    │
    ├─► SummaryStage            ← LLM 分析 diff → 结构化摘要 + 文件路由
    │       │
    │       └─► ReviewerStage   ← 并行执行 3 个 Reviewer（asyncio.gather）
    │               │                │
    │               │                ├─► Security Reviewer ──┐
    │               │                ├─► Logic Reviewer    ──┤ (当 tool_client 可用时
    │               │                └─► Quality Reviewer  ──┘  以 ReAct Agent 运行)
    │               │
    │               └─► AggregationStage    ← 去重 + DiffLineMapper 行号映射
    │                       │
    │                       └─► FalsePositiveFilterStage ← 正则硬规则 + 可选 LLM 验证
    │
    ▼
AsyncGitHubClient               ← post_review_comment（内联评论 + 汇总评论）
```

### 4.2 GitHub Action 模式（精简链路）

```
GitHub Action workflow
    │
    ▼
github_action_runner.py         ← 读取环境变量 → 同步获取 PR diff
    │
    ▼
PipelineOrchestrator            ← 无 Tool Server，Reviewer 降级为直接 LLM 调用
    │
    ▼
GitHubClient.post_review_comment ← 同步发布评论
```

### 4.3 Tool 调用链

```
Python ReviewerStage (LangChain AgentExecutor)
    │  tool call: get_file_content("src/Service.java")
    ▼
definitions.py @tool            ← 封装为 LangChain tool
    │
    ▼
JavaToolClient                  ← httpx POST /api/v1/tools/file-content
    │  Header: X-Session-Id + X-Tool-Secret
    ▼
ToolServerController (:9090)    ← 验证 secret → 查找 session → 分发
    │
    ▼
GetFileContentTool              ← FileAccessSandbox 路径校验 → 读取文件
    │
    ▼
ToolResult                      ← success=true, result=文件内容
```

---

## 5. 核心模块详解

### 5.1 Pipeline 引擎（Python Agent）

**位置**: `services/agent/app/agent/pipeline/`

**设计意图**: 将代码审查拆解为可组合的 Stage，每个 Stage 只负责一件事。Stage 之间通过 `PipelineContext` 传递数据，禁止直接耦合。

**PipelineContext 关键字段**:

| 字段 | 阶段 | 说明 |
|------|------|------|
| `diff_text` | 输入 | 完整 diff 文本 |
| `llm` | 输入 | LangChain ChatModel 实例 |
| `tool_client` | 输入 | Java Tool Server 客户端（可 None） |
| `file_diffs` | 输入 | 按文件拆分的 diff |
| `summary` | Stage 1 输出 | 变更摘要 |
| `file_groups` | Stage 1 输出 | 文件→审查维度路由 |
| `review_results` | Stage 2 输出 | 各 Reviewer 的 JSON 结果 |
| `final_issues` | Stage 3 输出 | 聚合后的 Issue 列表 |
| `filter_stats` | Stage 4 输出 | 误报过滤统计 |

**修改风险**:
- `PipelineContext` 是全局数据总线，添加字段影响所有 Stage
- `PipelineStage.execute()` 的签名变更会破坏所有 Stage

### 5.2 Reviewer Stage（Python Agent）

**位置**: `services/agent/app/agent/pipeline/stages/reviewer.py`

**设计意图**: 根据是否有 Tool Server 可用，自动选择执行模式：
- **有 Tool Server**: LangChain ReAct Agent（create_tool_calling_agent + AgentExecutor），最多 8 轮 tool 调用
- **无 Tool Server**: 直接 `llm.with_structured_output()` 结构化输出，更快更便宜

**关键细节**:
- 每个Reviewer 拿到的 diff 是经过 `file_groups` 过滤的——只看和该维度相关的文件
- 如果过滤后的 diff 超过原始 diff 的 85%，则回退到全量 diff（避免丢失上下文）
- ReAct Agent 的 JSON 输出可能被 markdown 代码块包裹，有 fallback 解析链

**修改风险**: `_run_with_tools()` 中的 `max_iterations=8` 和 JSON 输出约束是经验值，调大会增加 token 消耗和延迟

### 5.3 Aggregation Stage（Python Agent）

**位置**: `services/agent/app/agent/pipeline/stages/aggregation.py`

**设计意图**: 聚合 3 个 Reviewer 的结果，做三件事：
1. **去重**: 最多保留 50 个 issue，按严重程度排序
2. **行号映射**: 通过 `DiffLineMapper` 将 LLM 报告的 diff 上下文行号转为实际文件行号
3. **最终裁决**: LLM 做最终判断（has_critical、summary、highlights）

**DiffLineMapper 关键逻辑** (`diff_parser.py`):
- 单遍线性扫描 unified diff
- `@@ -old,count +new,count @@` 头记录 new_start
- `+` 行记录映射并推进 new_file_line
- `-` 行不推进
- ` ` (空格) 行推进但不记录映射

### 5.4 False Positive Filter（Python Agent）

**位置**: `services/agent/app/agent/false_positive_filter.py` + `config/false_positive_rules.yaml`

**设计意图**: 两阶段过滤，降低误报率。

**Stage 1 — HardExclusionRules**: 纯正则匹配，零 LLM 成本
- DOS/资源耗尽、通用性能建议、测试文件、文档文件、非 C/C++ 内存安全
- 支持从 YAML 加载规则，fallback 到硬编码默认值
- 基于 17+ 条 precedent（已知误报模式，如 JPA @Query 不是 SQL 注入）

**Stage 2 — LLM Verification** (可选): 逐条验证低置信度发现
- 将 finding + 上下文 + precedents 发给 LLM
- LLM 返回 `{"is_real": bool, "confidence": float, "reasoning": string}`

**重要**: 被 filter 标记为 `excluded` 的 issue 不会从列表中删除，只是 `confidence=0.0` + `filter_metadata.excluded=True`。下游可以自行决定是否展示。

### 5.5 Java Gateway — Tool Server

**位置**: `services/gateway/src/main/java/com/diffguard/adapter/toolserver/`

**设计意图**: 为 Python Agent 的 Reviewer 提供 6 种代码理解工具。通过 session 隔离不同审查请求。

**Session 管理** (`ToolSessionManager`):
- 每次 Python 审查请求创建一个 Session（UUID v4）
- Session TTL = 10 分钟，过期自动清理
- 重型资源（CodeGraph、SemanticSearchTool）按 projectDir 缓存共享，避免并发会话重复扫描
- `FileAccessSandbox` 强制路径白名单，防止路径穿越

**6 个 Tool**:
| Tool | 实现类 | 数据源 |
|------|--------|--------|
| `get_file_content` | GetFileContentTool | 直接读取文件（受 Sandbox 约束） |
| `get_diff_context` | GetDiffContextTool | Session 中缓存的 diff entries |
| `get_method_definition` | GetMethodDefinitionTool | AST 分析结果 |
| `get_call_graph` | GetCallGraphTool | CodeGraph（BFS 遍历） |
| `get_related_files` | GetRelatedFilesTool | CodeGraph（依赖/继承关系） |
| `semantic_search` | SemanticSearchTool | CodeRAG（TF-IDF 或 OpenAI Embedding） |

### 5.6 Java Gateway — AST/CodeGraph/RAG

**AST Analysis** (`domain/ast/`):
- `ASTAnalyzer`: 单文件解析，提取类/方法/调用边/控制流/字段访问/数据流
- `ProjectASTAnalyzer`: 全项目扫描 + 跨文件调用图构建
- `ASTCache`: Caffeine 缓存，key 为文件内容哈希
- SPI 扩展点：`LanguageASTProvider` → 目前只有 `JavaASTProvider`

**CodeGraph** (`domain/codegraph/`):
- 4 遍构建：FILE+CLASS 节点 → METHOD 节点 → 继承/调用/import 边 → 跨文件调用边
- 节点类型：FILE / CLASS / INTERFACE / METHOD
- 边类型：CONTAINS / EXTENDS / IMPLEMENTS / CALLS / IMPORTS
- 节点 ID 格式：`method:{filePath}:{className}.{methodName}({paramTypes})`

**CodeRAG** (`domain/coderag/`):
- 3 阶段：文件扫描+切片 → 构建词表 → 向量化+存储
- 两种 Embedding：LocalTFIDFProvider（零依赖） / OpenAiEmbeddingProvider（可选）
- 向量存储：InMemoryVectorStore（可替换为 Redis）
- `searchRelatedCode()` 自动排除 diff 文件自身

---

## 6. 重要文件说明

### 6.1 必须谨慎修改的文件（高风险）

| 文件 | 作用 | 修改风险 |
|------|------|---------|
| `services/agent/app/agent/pipeline/stages/base.py` | PipelineContext + PipelineStage 抽象 | 修改字段影响所有 Stage |
| `services/agent/app/agent/pipeline_orchestrator.py` | Pipeline 编排 + 分块逻辑 | 影响整个审查流程 |
| `services/agent/app/agent/llm_utils.py` | LLM 工厂 + 重试逻辑 | 影响 LLM 调用可靠性 |
| `services/agent/app/agent/false_positive_filter.py` | 两阶段误报过滤 | 直接影响审查结果质量 |
| `services/agent/app/models/schemas.py` | API 数据模型 | 影响所有 HTTP 接口 |
| `services/gateway/.../ToolSessionManager.java` | Session 生命周期管理 | 影响并发安全 |
| `services/gateway/.../CodeGraphBuilder.java` | 代码图谱构建 | 影响调用链分析 |
| `services/gateway/.../WebhookDispatcher.java` | Webhook→Agent 转发 | 影响审查触发链路 |

### 6.2 修改需注意但风险可控的文件（中风险）

| 文件 | 作用 | 注意事项 |
|------|------|---------|
| `services/agent/app/agent/pipeline/stages/reviewer.py` | 并行审查执行 | `max_iterations` 和 JSON 解析 fallback 是经验值 |
| `services/agent/app/agent/pipeline/stages/aggregation.py` | 聚合+行号映射 | `DiffLineMapper` 逻辑依赖 unified diff 格式假设 |
| `services/agent/app/agent/diff_parser.py` | Diff 行号映射 | 核心算法，修改需充分测试 |
| `services/agent/app/github/client.py` | GitHub API 客户端 | API 变更可能影响评论格式 |
| `services/agent/config/false_positive_rules.yaml` | 误报规则库 | 新增规则需评估对审查结果的影响 |
| `services/gateway/.../ConfigLoader.java` | 三层配置加载 | 深度合并逻辑需理解 Jackson 树操作 |

### 6.3 可安全修改的文件（低风险）

| 文件 | 作用 |
|------|------|
| `services/agent/app/llm/prompts/pipeline/*.txt` | Prompt 模板，独立修改 |
| `services/agent/app/tools/definitions.py` | LangChain @tool 定义 |
| `services/agent/app/metrics.py` | 可观测性指标 |
| `services/gateway/.../FileAccessSandbox.java` | 沙箱规则 |
| `services/agent/app/agent/pipeline/pipeline-config.yaml` | Pipeline DSL 配置 |

### 6.4 配置文件

| 文件 | 加载方式 | 作用 |
|------|---------|------|
| `services/gateway/config/application.yml` | `ConfigLoader` 三层加载 | Java Gateway 运行时配置 |
| `shared/config/review-config-template.yml` | 内置到 jar 中作为默认值 | 默认配置模板 |
| `services/agent/config/false_positive_rules.yaml` | `_RuleLoader` 按路径加载 | 误报规则库 |
| `services/agent/app/agent/pipeline/pipeline-config.yaml` | `load_pipeline_config()` | Pipeline Stage 编排配置 |
| `action.yml` | GitHub Action 运行时加载 | GitHub Action 输入/输出定义 |

---

## 7. Prompt 管理

### 7.1 存储结构

所有 Prompt 模板存储在 `services/agent/app/llm/prompts/` 目录下，纯文本文件。

### 7.2 加载方式

通过 `llm_utils.py` 的 `load_prompt(name)` 函数加载，参数是相对于 prompts 目录的路径：
```python
system = load_prompt("pipeline/security-system.txt")
user_tpl = load_prompt("pipeline/security-user.txt")
```

### 7.3 模板变量

使用 `{{variable_name}}` 占位符，调用时 `.replace()` 替换：
```python
user = user_tpl.replace("{{diff}}", diff_content).replace("{{summary}}", summary_text)
```

### 7.4 安全

`sanitize_diff_for_prompt()` 防止 diff 内容中的 XML 标签破坏 prompt 边界：
```python
diff_text.replace("</diff_input>", "<\\/diff_input>")
```

---

## 8. 开发规范

### 8.1 命名规范

- **Java**: 标准 Java 命名（PascalCase 类，camelCase 方法/变量）
- **Python**: PEP 8（snake_case 函数/变量，PascalCase 类）
- **Prompt 文件**: `{reviewer}-{role}.txt`，如 `security-system.txt`

### 8.2 分层规范

**Java Gateway 严格遵守分层**:
```
adapter/     → 入站适配（Webhook、ToolServer）
domain/      → 核心领域逻辑（AST、CodeGraph、RAG、Tool 实现）
infrastructure/ → 基础设施（配置、Git 操作、通用工具）
exception/   → 异常定义
```
- adapter 可以调用 domain 和 infrastructure
- domain 不能依赖 adapter
- infrastructure 不能依赖 adapter

**Python Agent 分层**:
```
main.py              → HTTP 入口
agent/               → 核心审查逻辑
  pipeline/          → Pipeline Stage 实现
agent/llm_utils.py   → LLM 抽象
tools/               → Tool 定义
models/              → 数据模型
github/              → GitHub API 客户端
```

### 8.3 API Key 安全

- **禁止**明文写入配置文件或代码
- Java 侧通过 `api_key_env` 指定环境变量名，`resolveApiKey()` 从环境读取
- Python 侧通过 `LlmConfig.api_key_env` + `model_validator` 自动解析
- HTTP 请求中传递的是 `api_key_env`（环境变量名），不是 API Key 本身

### 8.4 异常处理

- Pipeline Stage 异常必须被捕获并降级为 FAILED 状态，**禁止中断整条 Pipeline**
- Tool 调用异常必须返回描述性错误字符串，**禁止未捕获异常传播**
- False Positive Filter 异常时保留所有 issues（fail-open）

---

## 9. AI 修改代码指南

### 9.1 修改前必读

1. 先看 `services/agent/app/agent/pipeline/stages/base.py` 理解 `PipelineContext` 的所有字段
2. 先看 `services/agent/app/models/schemas.py` 理解 API 数据模型
3. 先看对应的 Prompt 文件理解 LLM 的输入/输出格式

### 9.2 新增 Reviewer 维度

1. 在 `services/agent/app/llm/prompts/pipeline/` 添加 `xxx-system.txt` 和 `xxx-user.txt`
2. 在 `pipeline-config.yaml` 的 reviewer stage 下添加新 reviewer 定义
3. Prompt 中必须包含 JSON 输出格式约束和强制排除项

### 9.3 新增 Pipeline Stage

1. 继承 `PipelineStage`，实现 `name` 属性和 `execute()` 方法
2. 在 `pipeline_config.py` 的 `build_pipeline_from_config()` 中注册新 type
3. 在 `pipeline-config.yaml` 中添加 stage 定义

### 9.4 新增 Tool

1. **Java 侧**: 在 `domain/agent/tools/` 实现 `AgentTool` 接口
2. **Java 侧**: 在 `ToolSessionManager.Session` 构造函数中注册
3. **Java 侧**: 在 `ToolServerController` 添加路由
4. **Python 侧**: 在 `tools/definitions.py` 添加 `make_xxx_tool()` 工厂函数
5. **Python 侧**: 在 `reviewer.py` 的 `_run_with_tools()` 中添加到 tools 列表

### 9.5 修改 Prompt

- 修改 prompt 文件本身即可，不需要改代码
- **必须**保持 JSON 输出格式约束不变（否则下游解析会失败）
- **必须**保持强制排除项（否则误报率会上升）

### 9.6 禁止事项

- **禁止** Python Agent 直接访问文件系统（必须通过 Tool Server）
- **禁止** Tool 包含写操作或副作用
- **禁止** 跨 Session 复用 Tool Session ID
- **禁止** 明文存储 API Key
- **禁止** Stage 异常中断 Pipeline
- **禁止** Prompt 硬编码在 Python 代码中（必须放 .txt 文件）
- **禁止** 修改 `IssuePayload` 的 JSON schema 而不同步修改 Java 侧的解析逻辑

### 9.7 架构一致性检查清单

修改代码后，确认以下要点：

- [ ] Pipeline Stage 之间是否仍然通过 `PipelineContext` 传递数据？
- [ ] 新增的 LLM 调用是否使用了 `invoke_with_retry()` 而不是直接调用？
- [ ] 新增的 Tool 是否是只读的？
- [ ] API Key 是否只通过环境变量引用？
- [ ] Prompt 文件中的 JSON 输出格式是否和 Pydantic model 一致？
- [ ] 异常路径是否有 fallback 处理？

---

## 10. 项目风险区域

### 10.1 高风险目录

| 目录 | 风险原因 |
|------|---------|
| `services/agent/app/agent/pipeline/stages/` | Pipeline 核心，任何修改都可能影响审查结果 |
| `services/agent/app/agent/false_positive_filter.py` | 直接决定哪些 issue 被过滤 |
| `services/agent/app/agent/diff_parser.py` | 行号映射逻辑，错误会导致 GitHub 评论定位错 |
| `services/gateway/.../ToolSessionManager.java` | Session 管理涉及并发安全 |
| `services/agent/app/llm/prompts/pipeline/` | Prompt 变更直接影响 LLM 行为 |

### 10.2 隐式依赖

- `pipeline-config.yaml` 和 `pipeline_config.py` 必须保持同步：YAML 中的 stage type 必须在 Python 中有对应处理
- `false_positive_rules.yaml` 中的正则模式和 `false_positive_filter.py` 中的硬编码默认值是叠加关系（不是替换）
- `DiffLineMapper` 假设 diff 格式是标准 unified diff，如果 GitHub 改了 diff 格式会失效
- `WebhookDispatcher` 传递 `api_key_env` 而非 API key，Python 侧的 `LlmConfig.model_validator` 负责解析——两端必须保持一致

### 10.3 易误解的架构

- **`ReviewEngine` 类不存在**: 审查引擎不是一个单一类，而是 `PipelineOrchestrator` + 4 个 Stage 的组合
- **`PythonAgentClient` 在 Java 侧**: 这是 Java 调用 Python 的客户端，不是 Python 自身的类
- **两个 GitHub 客户端**: `github/client.py`（异步，Webhook 模式）和 `github_api.py`（同步，Action 模式）
- **ReAct Agent 是可选的**: 只有 Tool Server 可用时 Reviewer 才以 Agent 模式运行，否则退化为直接 LLM 调用
- **`_DEFAULT_PRECEDENTS` 和 YAML rules 是叠加的**: YAML 中的 precedents 会替换默认值，但 `_DEFAULT_*_PATTERNS` 始终会被添加

---

## 11. 快速上手路线

### Step 1 — 理解项目目标（2 分钟）

读 [README.md](README.md) 的 "Why DiffGuard" 和 "4-Stage Review Pipeline" 部分。

### Step 2 — 理解数据流（3 分钟）

读 [main.py](services/agent/app/main.py) 的 `/api/v1/webhook-review` 端点（第 148-231 行），理解一个审查请求的完整生命周期。

### Step 3 — 理解 Pipeline（5 分钟）

读 [pipeline_orchestrator.py](services/agent/app/agent/pipeline_orchestrator.py) 的 `_run_single()` 方法（第 92-149 行），理解 Stage 如何串联执行。

### Step 4 — 理解数据模型（3 分钟）

读 [schemas.py](services/agent/app/models/schemas.py)，理解 `ReviewRequest`、`ReviewResponse`、`IssuePayload` 的字段。

### Step 5 — 理解 Prompt（2 分钟）

读 [security-system.txt](services/agent/app/llm/prompts/pipeline/security-system.txt)，理解 Prompt 如何指导 LLM 输出结构化 JSON。

### Step 6 — 理解 Java Gateway（5 分钟）

读 [WebhookServer.java](services/gateway/src/main/java/com/diffguard/adapter/webhook/WebhookServer.java)，理解双端口架构（8080 Webhook + 9090 Tool Server）。

---

## 12. 测试

### 运行测试

```bash
# Python 测试
cd services/agent
uv sync --dev
uv run pytest tests/ -v --tb=short

# Java 测试
cd services/gateway
mvn -B verify
```

### 测试覆盖

| 模块 | 测试文件 |
|------|---------|
| Pipeline Stages | `test_pipeline_stages.py` |
| False Positive Filter | `test_false_positive_filter.py` |
| Diff Parser | `test_diff_parser.py` |
| Tool Definitions | `test_tools_definitions.py` |
| Tool Client | `test_tools_tool_client.py` |
| Agent Base | `test_agent_base.py` |
| LLM Utils | `test_agent_llm_utils.py` |
| Models | `test_models_schemas.py` |
| Java Webhook | `WebhookControllerTest.java`, `SignatureVerifierTest.java` |
| Java Tools | `GetCallGraphToolTest.java`, `FileAccessSandboxTest.java` 等 |

---

## 13. 环境变量参考

| 变量 | 用途 | 默认值 |
|------|------|--------|
| `DIFFGUARD_API_KEY` | LLM API Key | 无（必须设置） |
| `DIFFGUARD_API_BASE_URL` | LLM API 自定义地址 | Provider 默认地址 |
| `DIFFGUARD_PROVIDER` | LLM Provider (claude/openai) | `claude` |
| `DIFFGUARD_MODEL` | LLM 模型名 | `claude-sonnet-4-20250514` |
| `DIFFGUARD_LANGUAGE` | 审查输出语言 | `zh` |
| `DIFFGUARD_WEBHOOK_SECRET` | Webhook HMAC 密钥 | 无（跳过验证） |
| `DIFFGUARD_GITHUB_TOKEN` | GitHub API Token | 无 |
| `DIFFGUARD_TOOL_SECRET` | Tool Server 共享密钥 | 无 |
| `JAVA_TOOL_SERVER_URL` | Python 侧连接 Java Tool Server 的地址 | 空（不使用） |
| `DIFFGUARD_COMMENT_PR` | 是否发布 PR 评论 | `true` |
| `DIFFGUARD_EXCLUDE_DIRS` | 排除的目录 | 空 |
| `DIFFGUARD_ENABLE_FP_FILTER` | 是否启用误报过滤 | `true` |
| `DIFFGUARD_TIMEOUT_MINUTES` | 审查超时（分钟） | `10` |
| `AGENT_HOST` | Python Agent 监听地址 | `0.0.0.0` |
| `AGENT_PORT` | Python Agent 监听端口 | `8000` |
| `LOG_LEVEL` | Python Agent 日志级别 | `info` |
| `WEBHOOK_HMAC_SECRET` | Python 侧 Webhook HMAC 密钥 | 无 |

---

## 14. 违规判定标准

| 编号 | 违规行为 | 严重级别 |
|------|---------|---------|
| V-01 | Tool 包含写操作或副作用 | CRITICAL |
| V-02 | 明文存储 API Key | CRITICAL |
| V-03 | 跨 Session 复用 Tool Session | HIGH |
| V-04 | 输出不遵循 JSON Schema | HIGH |
| V-05 | Stage 异常中断 Pipeline | HIGH |
| V-06 | Prompt 硬编码在代码中 | MEDIUM |
| V-07 | Tool 异常未捕获直接抛出 | MEDIUM |
| V-08 | 修改 PipelineContext 未同步所有 Stage | MEDIUM |
| V-09 | 新增 LLM 调用未使用 retry wrapper | LOW |
