# DiffGuard 项目架构分析报告

> Phase 1 输出 | 2026-04-18

---

## 1. 项目概览

DiffGuard 是一个 **Git 集成的 AI Code Review CLI 工具**，通过 LLM 对 Git Diff 进行智能代码审查，支持 CLI 和 GitHub Webhook 两种触发方式。

| 维度 | 详情 |
|------|------|
| **语言** | Java 21 |
| **构建工具** | Maven (单模块) |
| **总源文件** | 51 主代码 + 30 测试 = 81 Java 文件 |
| **包数量** | 16 源码包 |
| **入口类** | `com.diffguard.DiffGuard` → `DiffGuardMain` (picocli) |

---

## 2. 技术栈

| 类别 | 技术 | 版本 | 用途 |
|------|------|------|------|
| CLI 框架 | picocli | 4.7.5 | 命令行解析 |
| Git 操作 | JGit | 6.8.0 | Diff 收集 |
| LLM 集成 | LangChain4j | 1.13.0 | AI Service / Tool Use / 结构化输出 |
| LLM Provider | OpenAI / Anthropic | - | Claude / GPT 适配 |
| AST 解析 | JavaParser | 3.26.3 | Java 源码结构分析 |
| JSON | Jackson | 2.17.0 | 序列化/反序列化 |
| 缓存 | Caffeine | 3.1.8 | AST 结果缓存 |
| HTTP Server | Javalin | 5.6.3 | Webhook 接收 |
| Token 计数 | jtokkit | 1.0.0 | Token 估算 |
| 测试 | JUnit 5 + Mockito | 5.10.2 / 5.11.0 | 单元测试 |

---

## 3. 项目架构

```
┌─────────────────────────────────────────────────────────┐
│                    Entry Points                         │
│  ┌──────────┐                    ┌──────────────────┐   │
│  │   CLI    │                    │   Webhook HTTP   │   │
│  │ picocli  │                    │   Javalin        │   │
│  └────┬─────┘                    └────────┬─────────┘   │
│       │                                   │             │
├───────┼───────────────────────────────────┼─────────────┤
│       ▼                                   ▼             │
│  ┌──────────┐                    ┌──────────────────┐   │
│  │ Review   │                    │   Review         │   │
│  │ Command  │                    │   Orchestrator   │   │
│  └────┬─────┘                    └────────┬─────────┘   │
│       │                                   │             │
├───────┼───────────────────────────────────┼─────────────┤
│       │          Core Pipeline            │             │
│       │                                   │             │
│       ▼                                   ▼             │
│  ┌──────────────────────────────────────────────┐       │
│  │          DiffCollector (JGit)                 │       │
│  │    Git staged diff / ref-to-ref diff          │       │
│  └──────────────────┬───────────────────────────┘       │
│                     │                                    │
│                     ▼                                    │
│  ┌──────────────────────────────────────────────┐       │
│  │     ASTEnricher → ASTAnalyzer → ASTCache     │       │
│  │     ASTContextBuilder                         │       │
│  │     (JavaParser: class/method/call/control)   │       │
│  └──────────────────┬───────────────────────────┘       │
│                     │                                    │
│          ┌──────────┴──────────┐                        │
│          ▼                     ▼                         │
│  ┌───────────────┐   ┌──────────────────────┐           │
│  │ ReviewService │   │ MultiStageReview     │           │
│  │ (单次审查)    │   │ Service (流水线)     │           │
│  └───────┬───────┘   └──────────┬───────────┘           │
│          │                      │                        │
├──────────┼──────────────────────┼────────────────────────┤
│          │     LLM Layer        │                        │
│          ▼                      ▼                        │
│  ┌──────────────────────────────────────────────┐       │
│  │              LlmClient                        │       │
│  │  Phase 1: AiServices 结构化输出 + Tool Use    │       │
│  │  Phase 2: Raw HTTP + 手动 JSON 解析          │       │
│  │  + 重试 / 并发批处理 / Token 追踪             │       │
│  └──────────────────┬───────────────────────────┘       │
│                     │                                    │
│          ┌──────────┴──────────┐                        │
│          ▼                     ▼                         │
│  ┌───────────────┐   ┌──────────────────────┐           │
│  │ LlmProvider   │   │ ReviewToolProvider   │           │
│  │ (OpenAI/      │   │ (readFile/listMethods│           │
│  │  Claude)      │   │  checkImports)       │           │
│  └───────────────┘   └──────────────────────┘           │
│                                                          │
├──────────────────────────────────────────────────────────┤
│                    Output Layer                          │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────┐    │
│  │ Console      │  │ Markdown     │  │ GitHub API │    │
│  │ Formatter    │  │ Formatter    │  │ Client     │    │
│  └──────────────┘  └──────────────┘  └────────────┘    │
└──────────────────────────────────────────────────────────┘
```

---

## 4. Code Review Pipeline 详解

### 4.1 CLI 路径 (`diffguard review`)

```
1. ConfigLoader.load()     → 三级配置加载（项目 > 用户 > 默认）
2. DiffCollector.collect()  → JGit 收集 git diff → List<DiffFileEntry>
3. ASTEnricher.enrich()    → JavaParser AST 分析 → 上下文注入
4. ReviewService.review()   → 缓存检查 + PromptBuilder + LlmClient
   或 MultiStageReviewService.review() → 三阶段流水线
5. ConsoleFormatter         → 控制台输出
6. Exit code: 0/1          → 1 = 有 CRITICAL issue（除非 --force）
```

### 4.2 Webhook 路径 (GitHub PR)

```
1. Javalin HTTP             → POST /webhook/github
2. RateLimiter              → IP 限流
3. SignatureVerifier        → HMAC 签名验证
4. GitHubPayloadParser      → 解析 PR payload
5. ReviewOrchestrator       → 异步线程池 + 5 分钟超时
6. git fetch + DiffCollector → 获取 PR diff
7. ASTEnricher + ReviewService → 审查
8. MarkdownFormatter + GitHubApiClient → 发布 PR 评论
```

### 4.3 Multi-Stage Pipeline (流水线模式)

```
Stage 1: DiffSummaryAgent    → 变更摘要 + 风险评估
Stage 2: 并行审查
   ├── SecurityReviewer      → 安全审查（SQL注入、命令注入等）
   ├── LogicReviewer         → 逻辑审查（并发、空指针等）
   └── QualityReviewer       → 质量审查（命名、复杂度等）
Stage 3: AggregationAgent    → 合并 + 去重 + 最终定级
```

---

## 5. 核心模块详解

### 5.1 CLI 层 (`com.diffguard.cli`)

| 类 | 职责 |
|----|------|
| `DiffGuard` | Main class，委托 `DiffGuardMain` |
| `DiffGuardMain` | picocli Top-level command |
| `ReviewCommand` | `review` 子命令：编排完整审查流程 |
| `InstallCommand` | `install` 子命令：安装 Git hooks |
| `UninstallCommand` | `uninstall` 子命令：卸载 Git hooks |
| `ServerCommand` | `server` 子命令：启动 Webhook 服务 |
| `VersionProvider` | 读取 version.properties |

### 5.2 Review 层 (`com.diffguard.review`)

| 类 | 职责 |
|----|------|
| `ReviewService` | 单次审查：缓存 → Prompt → LLM → 结果合并 |
| `ReviewOrchestrator` | Webhook 审查编排：异步执行 + 超时保护 |
| `ReviewCache` | 文件级审查缓存（Caffeine） |

### 5.3 Agent 层 (`com.diffguard.agent`)

| 类 | 职责 |
|----|------|
| `StructuredReviewService` | LangChain4j AiServices 声明式接口 |
| `MultiStageReviewService` | 三阶段流水线编排 |
| `DiffSummaryAgent` | Stage 1：Diff 摘要 |
| `SecurityReviewer` | Stage 2：安全审查 |
| `LogicReviewer` | Stage 2：逻辑审查 |
| `QualityReviewer` | Stage 2：质量审查 |
| `AggregationAgent` | Stage 3：汇总聚合 |
| `TargetedReviewResult` | 单域审查结果 |

### 5.4 AST 层 (`com.diffguard.ast`)

| 类 | 职责 |
|----|------|
| `ASTAnalyzer` | JavaParser 核心：类/方法/调用图/控制流/imports |
| `ASTCache` | Caffeine 缓存（200 entries, 2h TTL） |
| `ASTContextBuilder` | Diff-aware 上下文构建 + Token 预算裁剪 |
| `ASTEnricher` | 门面类：编排 AST 分析流水线 |

**已有 AST 能力：**
- 类/接口/枚举/Record 声明提取
- 方法签名提取（返回类型、参数、可见性、修饰符、注解）
- 文件内调用图（caller → callee）
- 控制流提取（IF/FOR/WHILE/TRY_CATCH/SWITCH/SYNCHRONIZED）
- Import 列表
- Diff 感知的上下文构建（优先展示变更区域）
- Token 预算感知的三级裁剪策略

**AST 层缺失能力：**
- 跨文件调用图解析
- 数据流 / 污点分析
- 类型解析（无符号解析）
- 非 Java 文件支持

### 5.5 LLM 层 (`com.diffguard.llm`)

| 类 | 职责 |
|----|------|
| `LlmClient` | 中心 LLM 通信层：双阶段回退 + 重试 + 并发批处理 |
| `LlmProvider` | LLM 调用接口 |
| `LangChain4jClaudeAdapter` | Claude 适配器 |
| `LangChain4jOpenAiAdapter` | OpenAI 适配器 |
| `ReviewToolProvider` | LLM 可调用工具（readFile, listMethods, checkImports） |
| `FileAccessSandbox` | 文件访问沙箱 |
| `TokenTracker` | Token 消耗追踪 |
| `ProxyResponseDetector` | 代理响应检测 |

### 5.6 Webhook 层 (`com.diffguard.webhook`)

| 类 | 职责 |
|----|------|
| `WebhookServer` | Javalin HTTP 服务器 |
| `WebhookController` | Webhook 请求处理 |
| `SignatureVerifier` | HMAC-SHA256 签名验证 |
| `GitHubPayloadParser` | GitHub PR payload 解析 |
| `GitHubApiClient` | GitHub API 客户端（评论、diff） |
| `RateLimiter` | IP 级限流 |

---

## 6. 依赖关系图

```
DiffGuard (main)
    └── DiffGuardMain (picocli)
        ├── ReviewCommand ──────────┐
        │   ├── ConfigLoader        │
        │   ├── DiffCollector       │
        │   ├── ASTEnricher         │
        │   │   ├── ASTAnalyzer     │
        │   │   ├── ASTCache        │
        │   │   └── ASTContextBuilder│
        │   ├── ReviewService ──────┤
        │   │   ├── ReviewCache     │
        │   │   ├── PromptBuilder   │
        │   │   └── LlmClient ─────┤
        │   │       ├── LlmProvider │
        │   │       ├── Structured  │
        │   │       │   ReviewService│
        │   │       └── ReviewTool  │
        │   │           Provider    │
        │   └── MultiStageReview ──┤
        │       └── Service         │
        │           ├── DiffSummary │
        │           ├── Security    │
        │           ├── Logic       │
        │           ├── Quality     │
        │           └── Aggregation │
        ├── InstallCommand          │
        ├── UninstallCommand        │
        └── ServerCommand ──────────┘
            └── WebhookServer
                └── WebhookController
                    ├── RateLimiter
                    ├── SignatureVerifier
                    ├── GitHubPayloadParser
                    └── ReviewOrchestrator
                        └── (同 ReviewCommand 核心)
```

---

## 7. 数据模型

```
DiffFileEntry          → 单文件 diff 内容
    ├── filePath
    ├── content
    ├── tokenCount
    └── lineCount

ASTAnalysisResult      → 单文件 AST 分析结果
    ├── methods: List<MethodInfo>
    ├── callEdges: List<CallEdge>
    ├── classes: List<ClassInfo>
    ├── controlFlowNodes: List<ControlFlowNode>
    └── imports: List<String>

ReviewResult           → 完整审查结果
    ├── issues: List<ReviewIssue>
    ├── hasCriticalFlag: Boolean
    ├── filesReviewed: int
    ├── totalTokensUsed: int
    └── durationMs: long

ReviewIssue            → 单个审查发现
    ├── severity: Severity (CRITICAL/WARNING/INFO)
    ├── file, line, type
    └── message, suggestion

ReviewOutput           → LLM 结构化输出 (LangChain4j record)
    ├── has_critical, summary
    ├── issues: List<IssueRecord>
    ├── highlights, test_suggestions
```

---

## 8. 已识别的架构问题

### 8.1 严重问题

| # | 问题 | 影响 | 位置 |
|---|------|------|------|
| **S1** | `ReviewService.ownedClient` 线程不安全 | 并发 Webhook 审查可能创建多个 LlmClient，导致资源泄漏 | `ReviewService.java` |
| **S2** | `ProgressDisplay.setSilent()` 使用全局静态状态 | 并发 review 会互相干扰 | `ReviewOrchestrator.java` |
| **S3** | Webhook 路径未接入 Pipeline 模式 | Webhook 只能用单次审查，缺少多阶段能力 | `ReviewOrchestrator.java` |
| **S4** | `ASTEnricher` 读取当前磁盘文件 | Diff 可能对应旧版本文件，AST 上下文可能不匹配 | `ASTEnricher.java` |

### 8.2 设计问题

| # | 问题 | 建议 |
|---|------|------|
| **D1** | `ReviewIssue` (mutable class) 与 `IssueRecord` (record) 重复 | 统一为单一模型 |
| **D2** | `PromptBuilder.MAX_COMBINED_TOKENS` 硬编码 6000 | 应可配置 |
| **D3** | 调用图仅用方法名（非限定名） | 重载方法调用会混淆 |
| **D4** | `X-Forwarded-For` 未验证 | 可被伪造绕过限流 |
| **D5** | `DiffCollector.formatDiff` 未指定 UTF-8 | 非 UTF-8 系统可能乱码 |

### 8.3 技术债

| # | 问题 |
|---|------|
| **T1** | AST 不支持跨文件调用图（仅文件内） |
| **T2** | AST 不支持非 Java 文件 |
| **T3** | 无全局 Tool 调用限制（3 个并行 agent 各自 10 次 = 30 次） |
| **T4** | 截断算法粗暴（`substring(0, length * 2/3)` 循环） |
| **T5** | `LlmClientTest.multiplePromptsParallel` 存在 flaky test |

---

## 9. Phase 2-10 升级路线建议

基于当前架构分析，升级策略如下：

```
Phase 2: AST 增强
  → 扩展现有 ASTAnalyzer（已用 JavaParser）
  → 新增跨文件解析、类型解析、数据流分析
  → 优先级: 高（已有基础）

Phase 3: Code Graph
  → 新增 code_graph/ 包
  → 基于 AST 结果构建全局代码关系图
  → 需要 AST 的跨文件解析支持

Phase 4: Code RAG (最高优先级)
  → 新增 code_rag/ 包
  → 代码切片 + Embedding + FAISS 向量检索
  → 需要新增依赖（FAISS JNI 或 REST API）

Phase 5: Agent 架构
  → 新增 agent/core/ 包
  → ReAct Loop 实现
  → 替换当前 Pipeline 模式

Phase 6: Tool 系统
  → 扩展现有 ReviewToolProvider
  → 新增 Tool Registry + 更多工具

Phase 7: Memory 系统
  → 新增 project_memory/ 包
  → SQLite / JSON 持久化

Phase 8: Review Agents
  → 扩展现有 pipeline agents
  → 新增专用 Agent（Security/Performance/Architecture）

Phase 9: Review Strategy
  → 新增 strategy/ 包
  → LLM 驱动的策略选择

Phase 10: Bug Pattern Learning
  → 新增 learning/ 包
  → 历史问题分析 + 模式学习
```

---

## 10. 当前构建状态

| 检查项 | 状态 |
|--------|------|
| 编译 | ✅ 通过 |
| 测试 | ⚠️ 302 tests, 1 failure (LlmClientTest flaky) |
| 失败测试 | `LlmClientTest$ParallelExecution.multiplePromptsParallel` |
| 原因 | 并行 mock 竞态条件（已知 flaky test） |

---

*Phase 1 分析完成。下一步: Phase 2 AST 静态分析层增强。*
