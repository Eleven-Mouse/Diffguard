<p align="center">
  <img src="docs/images/logo.svg" alt="DiffGuard" width="120" height="120" />
</p>

<h1 align="center">DiffGuard</h1>

<p align="center">
  <strong>AI 驱动的多 Agent 智能代码审查系统</strong>
</p>

<p align="center">
  <a href="./README_EN.md">English</a> | 中文
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange" alt="Java 21" />
  <img src="https://img.shields.io/badge/Python-3.11+-blue" alt="Python 3.11+" />
  <img src="https://img.shields.io/badge/LangChain-0.3+-green" alt="LangChain" />
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="MIT License" />
  <img src="https://img.shields.io/badge/PRs-Welcome-brightgreen" alt="PRs Welcome" />
</p>

---

## 项目简介

DiffGuard 是一个面向开发团队的 AI 代码审查系统，通过 **多 Agent 协作**、**AST 语义理解** 和 **代码知识图谱** 提供深度、精准的自动化 Code Review。

与传统的 LLM 单次调用审查不同，DiffGuard 将代码审查拆解为多个专业维度——安全、性能、架构，由独立的 AI Agent 并行审查后聚合结论，并支持 Agent 间的知识共享。同时，内置的静态规则引擎可在 **零 LLM 开销** 下拦截常见问题。

### 为什么选择 DiffGuard？

| 痛点 | DiffGuard 的解决方案 |
|------|----------------------|
| 人工 Code Review 耗时且标准不一 | AI 多维度自动审查，输出结构化结果 |
| 单次 LLM 调用容易遗漏深层问题 | 多 Agent 并行 + 共享记忆，交叉验证 |
| LLM 缺乏代码上下文导致误报 | 6 种代码上下文工具（AST / 调用链 / 语义搜索） |
| AI 调用成本高 | 静态规则前置过滤 + 两级缓存 + Token 预算控制 |
| 难以集成到现有工作流 | Git Hook + GitHub Webhook 双模式接入 |

---

## 核心特性

### 三种审查模式

| 模式 | 架构 | 适用场景 | 延迟 |
|------|------|----------|------|
| **Simple** | 单次 LLM 调用 | 快速检查、日常提交 | 低 |
| **Pipeline** | 3 阶段流水线（摘要 → 并行审查 → 聚合） | 中等复杂度变更 | 中 |
| **Multi-Agent** | 策略规划 + 并行 ReAct Agent + 共享记忆 | 大型 PR、高风险变更 | 较高 |

### 智能体系统

- **Security Agent** — SQL 注入、XSS、命令注入、硬编码密钥、路径遍历、SSRF、认证缺陷
- **Performance Agent** — N+1 查询、IO 密集循环、资源泄漏、低效数据结构
- **Architecture Agent** — 层次违规、职责混乱、循环依赖、过度耦合

### 深度代码理解

- **AST 分析** — 基于 JavaParser 的语法树解析，提取方法签名、调用链、控制流、数据流
- **代码知识图谱** — 跨文件依赖关系图，支持影响分析（BFS，最大深度 3）
- **Code RAG** — 多粒度代码切片 + 向量检索（TF-IDF / OpenAI Embedding）
- **6 种 Agent 工具** — 文件内容、Diff 上下文、方法定义、调用链、关联文件、语义搜索

### 工程化能力

- **静态规则引擎** — 零 LLM 成本，正则匹配 SQL 注入、硬编码密钥、危险函数、复杂度
- **两级缓存** — 内存（Caffeine） + 磁盘持久化，SHA-256 缓存键，GZIP 压缩
- **弹性架构** — 熔断器、限流器、指数退避重试、优雅降级
- **可观测性** — Micrometer 指标 + Prometheus 端点
- **异步消息队列** — RabbitMQ 解耦审查任务，支持死信队列

---

## 架构设计

```
                        ┌─────────────────────────────────────────────┐
                        │              DiffGuard 整体架构               │
                        └─────────────────────────────────────────────┘

  ┌──────────────┐                          ┌──────────────────────────┐
  │   Git Hook   │                          │   GitHub Webhook (PR)    │
  │ (pre-commit  │                          │  HMAC 签名验证            │
  │  /pre-push)  │                          │  IP 限流 (30 req/min)     │
  └──────┬───────┘                          └──────────┬───────────────┘
         │                                             │
         ▼                                             ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │                     Java Gateway (Javalin)                       │
  │  ┌────────────┐  ┌──────────────┐  ┌──────────────────────────┐ │
  │  │ CLI 入口    │  │ Webhook 控制器│  │ Tool Server (端口 9090)  │ │
  │  │ (Picocli)   │  │ (端口 8080)  │  │ 会话管理 + 工具路由      │ │
  │  └──────┬─────┘  └──────┬───────┘  └───────────▲──────────────┘ │
  │         │               │                       │                │
  │  ┌──────▼───────────────▼───────────────────────┴─────────────┐ │
  │  │                    服务编排层                                │ │
  │  │  DiffCollector → ASTEnricher → RuleEngine → ReviewEngine   │ │
  │  └─────────────────────────┬──────────────────────────────────┘ │
  │                            │                                    │
  │  ┌─────────────────────────▼──────────────────────────────────┐ │
  │  │                      领域层                                 │ │
  │  │  ┌──────────┐  ┌─────────────┐  ┌──────────┐              │ │
  │  │  │ AST 分析  │  │ 代码知识图谱 │  │ Code RAG │              │ │
  │  │  │ JavaParser│  │ 有向图 + BFS│  │ TF-IDF/  │              │ │
  │  │  │ SPI 多语言│  │ 影响分析    │  │ OpenAI   │              │ │
  │  │  └──────────┘  └─────────────┘  └──────────┘              │ │
  │  │  ┌──────────┐  ┌─────────────┐  ┌──────────┐              │ │
  │  │  │ 规则引擎  │  │ Agent 工具集 │  │ LLM 客户端│              │ │
  │  │  │ 4 条规则  │  │ 6 种工具    │  │ Claude/  │              │ │
  │  │  │ 零 LLM   │  │ 安全沙箱    │  │ OpenAI   │              │ │
  │  │  └──────────┘  └─────────────┘  └──────────┘              │ │
  │  └────────────────────────────────────────────────────────────┘ │
  │  ┌────────────────────────────────────────────────────────────┐ │
  │  │                    基础设施层                                │ │
  │  │  LlmClient (重试/批处理) │ Resilience4j │ 缓存 │ 持久化    │ │
  │  │  RabbitMQ Publisher      │ MySQL        │ Redis │ Metrics  │ │
  │  └────────────────────────────────────────────────────────────┘ │
  └──────────────────────┬─────────────────────────────────────────┘
                         │ HTTP REST + RabbitMQ
                         ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │                   Python Agent Service (FastAPI)                 │
  │  ┌────────────────────────────────────────────────────────────┐ │
  │  │                    编排器层                                  │ │
  │  │  ┌──────────────────┐    ┌───────────────────────────────┐ │ │
  │  │  │ PipelineOrchestrator│  │ MultiAgentOrchestrator       │ │ │
  │  │  │ 摘要→审查→聚合     │  │ 策略规划→并行Agent→去重聚合  │ │ │
  │  │  └──────────────────┘    └───────────────────────────────┘ │ │
  │  └────────────────────────────────────────────────────────────┘ │
  │  ┌────────────────────────────────────────────────────────────┐ │
  │  │                    Agent 层 (LangChain ReAct)               │ │
  │  │  ┌──────────┐  ┌──────────────┐  ┌──────────────┐         │ │
  │  │  │ Security │  │ Performance  │  │ Architecture │         │ │
  │  │  │ Agent    │  │ Agent        │  │ Agent        │         │ │
  │  │  │ (权重1.2) │  │ (权重1.0)    │  │ (权重1.0)    │         │ │
  │  │  └──────────┘  └──────────────┘  └──────────────┘         │ │
  │  │  共享记忆 (AgentMemory): 跨 Agent 知识共享                    │ │
  │  │  策略规划器 (StrategyPlanner): 基于 Diff 特征动态分配权重       │ │
  │  └────────────────────────────────────────────────────────────┘ │
  │  ┌────────────────────────────────────────────────────────────┐ │
  │  │  ToolClient ←→ Java Tool Server (HTTP, 会话隔离)            │ │
  │  └────────────────────────────────────────────────────────────┘ │
  └──────────────────────────────────────────────────────────────────┘
```

---

## 快速开始

### 环境要求

- **Java 21**（Temurin 发行版推荐）
- **Python 3.11+**
- **Git**
- **LLM API Key**（OpenAI 或 Anthropic）

### 30 秒体验（CLI 模式）

```bash
# 1. 克隆项目
git clone https://github.com/kunxing/diffguard.git
cd diffguard

# 2. 构建网关
cd services/gateway && mvn clean package -DskipTests && cd ../..

# 3. 设置 API Key
export DIFFGUARD_API_KEY="sk-your-api-key-here"

# 4. 在任意 Git 项目中执行审查
java -jar services/gateway/target/diffguard-1.0.0.jar review --staged
```

---

## 安装步骤

### 方式一：本地 CLI 安装

```bash
# 构建 fat JAR
cd services/gateway
mvn clean package

# 安装 Git Hook（pre-commit + pre-push）
java -jar target/diffguard-1.0.0.jar install

# 卸载 Hook
java -jar target/diffguard-1.0.0.jar uninstall
```

安装后，每次 `git commit` 或 `git push` 将自动触发代码审查。

### 方式二：Docker Compose 部署（Server 模式）

```bash
# 配置环境变量
export DIFFGUARD_API_KEY="sk-your-api-key"
export DIFFGUARD_WEBHOOK_SECRET="your-webhook-secret"
export DIFFGUARD_GITHUB_TOKEN="ghp-your-token"

# 一键启动所有服务
docker compose up -d

# 查看服务状态
docker compose ps
```

启动后可访问：
- **Webhook 端点**: `http://localhost:8080/webhook/github`
- **Tool Server**: `http://localhost:9090`
- **Agent 健康检查**: `http://localhost:8000/api/v1/health`
- **RabbitMQ 管理界面**: `http://localhost:15672`
- **Prometheus 指标**: `http://localhost:9091/metrics`

---

## 配置说明

DiffGuard 采用 **三层配置合并** 策略：内置默认 → 项目级 `application.yml` → 用户目录覆盖。

核心配置项（完整模板见 [review-config-template.yml](shared/config/review-config-template.yml)）：

```yaml
# LLM 配置
llm:
  provider: openai                          # openai | claude
  model: claude-haiku-4-5-20251001
  maxTokens: 16384
  temperature: 0.3
  timeout: 240
  apiKeyEnv: DIFFGUARD_API_KEY              # 从环境变量读取，不存储明文
  baseUrl: ""                               # 自定义 API 端点（支持代理）

# 规则配置
rules:
  enabled: [security, bug-risk, code-style, performance]
  threshold: info

# 审查选项
review:
  maxDiffFiles: 20                          # 单次审查最大文件数
  maxTokensPerFile: 4000                    # 单文件最大 Token 数
  language: zh                              # 输出语言
  pipelineMode: false                       # 启用 Pipeline 模式
  multiAgentMode: false                     # 启用 Multi-Agent 模式

# Webhook 服务（Server 模式）
webhook:
  port: 8080
  secretEnv: DIFFGUARD_WEBHOOK_SECRET
  githubTokenEnv: DIFFGUARD_GITHUB_TOKEN
  repoMappings:
    "owner/repo": "/path/to/local/repo"
```

### 环境变量

| 变量名 | 用途 | 必需 |
|--------|------|------|
| `DIFFGUARD_API_KEY` | LLM API Key | 是 |
| `DIFFGUARD_API_BASE_URL` | 自定义 API 端点 | 否 |
| `DIFFGUARD_WEBHOOK_SECRET` | GitHub Webhook 签名密钥 | Server 模式 |
| `DIFFGUARD_GITHUB_TOKEN` | GitHub API Token（用于发布 PR 评论） | Server 模式 |

### Agent 策略配置

策略规划器根据文件类型和风险级别动态调整 Agent 权重（`agent/strategy/config.yaml`）：

```yaml
categories:
  controller:
    security: 1.5
    architecture: 1.3
  dao:
    security: 2.0
    performance: 1.5
  config:
    security: 2.5
    performance: 0.3

risk_adjustments:
  high:
    security_delta: 0.5
    focus_areas: ["输入验证", "权限控制"]
```

---

## 使用示例

### CLI 审查

```bash
# 审查暂存区变更（pre-commit 场景）
java -jar diffguard.jar review --staged

# 审查两个分支之间的差异
java -jar diffguard.jar review --from main --to feature/login

# Pipeline 模式
java -jar diffguard.jar review --staged --pipeline

# Multi-Agent 模式
java -jar diffguard.jar review --staged --multi-agent

# 忽略严重问题，强制通过
java -jar diffguard.jar review --staged --force

# 指定配置文件
java -jar diffguard.jar review --staged --config /path/to/config.yml
```

### 输出示例

```
╔══════════════════════════════════════════════════════════════════╗
║  DiffGuard Code Review Report                                   ║
╠══════════════════════════════════════════════════════════════════╣
║                                                                  ║
║  📊 Summary: 3 files reviewed, 5 issues found                   ║
║                                                                  ║
║  [CRITICAL] UserService.java:42                                  ║
║  Type: SQL注入                                                    ║
║  Message: 字符串拼接构建 SQL 查询，存在 SQL 注入风险                ║
║  Suggestion: 使用 PreparedStatement 替代字符串拼接                ║
║                                                                  ║
║  [WARNING] OrderController.java:78                               ║
║  Type: 缺少权限校验                                                ║
║  Message: 删除接口未进行权限验证                                    ║
║  Suggestion: 添加 @PreAuthorize("hasRole('ADMIN')") 注解         ║
║                                                                  ║
║  [INFO] README.md:1                                               ║
║  Type: 文档规范                                                    ║
║  Message: 建议补充 API 使用示例                                    ║
║                                                                  ║
╚══════════════════════════════════════════════════════════════════╝
```

### GitHub Webhook PR 评论

Server 模式下，DiffGuard 会自动在 PR 中发布格式化的 Markdown 审查评论，包含严重级别标签、代码定位和修复建议。

---

## 核心流程说明

### Pipeline 模式流程

```
Diff 输入
    │
    ▼
┌──────────────┐
│ SummaryStage │  结构化输出：变更摘要、文件列表、变更类型、风险评级(1-5)
│ (LLM 结构化) │
└──────┬───────┘
       │
       ▼
┌──────────────────────────────────────┐
│          ReviewerStage               │
│  ┌──────────┐  ┌───────┐  ┌───────┐ │
│  │ Security │  │ Logic │  │Quality│ │
│  │ Reviewer │  │Reviewer│ │Reviewer│ │
│  │ (ReAct)  │  │(ReAct) │ │(ReAct) │ │
│  └────┬─────┘  └───┬───┘  └──┬────┘ │
│       │  并行执行    │         │      │
└───────┼────────────┼─────────┼──────┘
        │            │         │
        ▼            ▼         ▼
┌──────────────────────────────────────┐
│        AggregationStage              │
│  去重 + 保留最高严重级别 + 综合总结    │
└──────────────┬───────────────────────┘
               │
               ▼
        结构化审查报告
```

### Multi-Agent 模式流程

```
Diff 输入
    │
    ▼
┌─────────────────────┐
│   StrategyPlanner   │  文件分类 + 风险评估 + 权重计算
│   Diff 特征分析      │  → 启用哪些 Agent，各自的审查重点
└─────────┬───────────┘
          │
          ▼
┌─────────────────────────────────────────────┐
│          并行 Agent 执行 (asyncio.gather)     │
│                                               │
│  ┌────────────┐ ┌──────────────┐ ┌──────────┐│
│  │  Security  │ │ Performance  │ │Architecture│
│  │   Agent    │ │    Agent     │ │   Agent    ││
│  │  (ReAct)   │ │   (ReAct)    │ │  (ReAct)   ││
│  │  权重: 1.2  │ │  权重: 1.0   │ │ 权重: 1.0  ││
│  └─────┬──────┘ └──────┬───────┘ └─────┬─────┘│
│        │               │               │       │
│        └───────┬───────┴───────┬───────┘       │
│                │  共享记忆      │                │
│        ┌───────▼───────────────▼───────┐       │
│        │     AgentMemory               │       │
│        │  · 跨 Agent 知识共享           │       │
│        │  · 后续 Agent 可见前置发现      │       │
│        └───────────────────────────────┘       │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────┐
│           结果聚合                     │
│  按 file:line:type 去重               │
│  合并 has_critical 标记               │
└──────────────┬───────────────────────┘
               │
               ▼
        结构化审查报告
```

---

## 项目结构

```
DiffGuard/
├── services/
│   ├── gateway/                          # Java 网关服务
│   │   ├── pom.xml                       # Maven 构建（Java 21, 18 个依赖）
│   │   ├── Dockerfile                    # 基于 eclipse-temurin:21-jre
│   │   └── src/main/java/com/diffguard/
│   │       ├── DiffGuard.java            # 程序入口
│   │       ├── cli/                      # CLI 命令层（Picocli）
│   │       │   ├── DiffGuardMain.java    # 主命令（review/install/uninstall/server）
│   │       │   ├── ReviewCommand.java    # 审查命令
│   │       │   ├── InstallCommand.java   # Git Hook 安装
│   │       │   ├── ServerCommand.java    # Webhook 服务器
│   │       │   └── UninstallCommand.java # Hook 卸载
│   │       ├── adapter/                  # 适配器层
│   │       │   ├── webhook/              # GitHub Webhook 接入
│   │       │   │   ├── WebhookServer.java       # Javalin HTTP 服务器
│   │       │   │   ├── WebhookController.java   # Webhook 请求处理
│   │       │   │   ├── SignatureVerifier.java   # HMAC-SHA256 签名验证
│   │       │   │   ├── RateLimiter.java         # IP 限流（Caffeine）
│   │       │   │   ├── GitHubPayloadParser.java # PR 载荷解析
│   │       │   │   └── GitHubApiClient.java     # GitHub API 评论发布
│   │       │   └── toolserver/           # Agent 工具服务器
│   │       │       ├── ToolServerController.java  # 工具路由
│   │       │       └── ToolSessionManager.java    # 会话管理（TTL 10min）
│   │       ├── domain/                   # 领域层
│   │       │   ├── review/              # 审查引擎
│   │       │   │   ├── ReviewEngine.java         # 统一审查接口
│   │       │   │   ├── ReviewService.java        # Simple 模式实现
│   │       │   │   ├── AsyncReviewEngine.java    # 异步轮询引擎
│   │       │   │   ├── ReviewCache.java          # 两级缓存
│   │       │   │   └── model/                    # ReviewResult/Issue/Severity
│   │       │   ├── agent/               # Agent 工具体系
│   │       │   │   ├── core/            # AgentContext/AgentTool/ToolResult
│   │       │   │   ├── tools/           # 6 种工具实现 + 安全沙箱
│   │       │   │   ├── python/          # Python Agent HTTP 客户端
│   │       │   │   └── ToolRegistry.java
│   │       │   ├── ast/                 # AST 语义分析
│   │       │   │   ├── ASTAnalyzer.java         # JavaParser 单文件分析
│   │       │   │   ├── ASTEnricher.java         # Diff AST 上下文增强
│   │       │   │   ├── ProjectASTAnalyzer.java  # 跨文件关系构建
│   │       │   │   ├── ASTContextBuilder.java    # Token 预算控制
│   │       │   │   ├── ASTCache.java            # Caffeine 缓存
│   │       │   │   ├── spi/                     # 多语言 AST SPI
│   │       │   │   └── model/                   # 数据模型
│   │       │   ├── codegraph/           # 代码知识图谱
│   │       │   │   ├── CodeGraph.java           # 有向图 + 查询 API
│   │       │   │   ├── CodeGraphBuilder.java    # 4 遍构建器
│   │       │   │   ├── GraphNode.java           # FILE/CLASS/METHOD/INTERFACE
│   │       │   │   └── GraphEdge.java           # CALLS/EXTENDS/IMPLEMENTS/IMPORTS
│   │       │   ├── coderag/             # 代码语义检索
│   │       │   │   ├── CodeRAGService.java      # 索引 + 检索门面
│   │       │   │   ├── CodeSlicer.java          # 多粒度切片
│   │       │   │   ├── LocalTFIDFProvider.java  # TF-IDF 嵌入（零依赖）
│   │       │   │   ├── OpenAiEmbeddingProvider.java  # OpenAI 嵌入
│   │       │   │   ├── InMemoryVectorStore.java      # 内存向量库
│   │       │   │   └── RedisVectorStore.java         # Redis 向量库
│   │       │   └── rules/               # 静态规则引擎
│   │       │       └── RuleEngine.java          # 4 条零 LLM 成本规则
│   │       ├── service/                 # 应用服务层
│   │       │   ├── ReviewApplicationService.java  # CLI 编排
│   │       │   ├── ReviewOrchestrator.java        # Server 编排（10 步流水线）
│   │       │   └── ReviewEngineFactory.java       # 引擎工厂
│   │       ├── infrastructure/          # 基础设施层
│   │       │   ├── llm/                # LLM 客户端
│   │       │   │   ├── LlmClient.java           # 重试 + 批处理 + 格式纠正
│   │       │   │   ├── provider/                # Claude/OpenAI HTTP Provider
│   │       │   │   └── BatchReviewExecutor.java # 并发批处理（max 3）
│   │       │   ├── messaging/          # RabbitMQ 消息队列
│   │       │   ├── persistence/        # MySQL 持久化（HikariCP）
│   │       │   ├── prompt/             # Prompt 模板引擎
│   │       │   ├── resilience/         # Resilience4j 弹性服务
│   │       │   ├── config/             # 三层配置加载
│   │       │   ├── git/                # JGit Diff 采集
│   │       │   ├── observability/      # Micrometer + Prometheus
│   │       │   └── output/             # 终端 UI（ANSI/Spinner/Markdown）
│   │       └── resources/
│   │           ├── application.yml      # 默认配置
│   │           └── db/schema.sql        # 数据库建表语句
│   │
│   └── agent/                            # Python Agent 服务
│       ├── pyproject.toml               # 依赖管理（hatchling）
│       ├── Dockerfile                   # 基于 python:3.12-slim
│       └── diffguard/
│           ├── main.py                  # FastAPI 入口（HTTP + RabbitMQ）
│           ├── config.py                # 环境变量配置
│           ├── models/schemas.py        # Pydantic 数据模型
│           ├── messaging/
│           │   └── rabbitmq_consumer.py # 异步消息消费（aio-pika）
│           ├── agent/
│           │   ├── base.py              # ReviewAgent 抽象基类
│           │   ├── registry.py          # 装饰器自注册 Agent Registry
│           │   ├── memory.py            # 跨 Agent 共享记忆
│           │   ├── strategy_planner.py  # Diff 特征分析 + 策略规划
│           │   ├── multi_agent_orchestrator.py   # 多 Agent 并行编排
│           │   ├── pipeline_orchestrator.py      # Pipeline 流水线编排
│           │   ├── builtin_agents/              # 内置 Agent 实现
│           │   │   ├── security.py              # 安全审查 Agent (ReAct)
│           │   │   ├── performance.py           # 性能审查 Agent (ReAct)
│           │   │   └── architecture.py          # 架构审查 Agent (ReAct)
│           │   ├── pipeline/                    # Pipeline 阶段
│           │   │   └── stages/
│           │   │       ├── summary.py           # Diff 摘要（结构化输出）
│           │   │       ├── reviewer.py          # 并行审查器（ReAct）
│           │   │       ├── aggregation.py       # 结果聚合
│           │   │       ├── static_rules.py      # 静态规则（零 LLM）
│           │   │       └── pipeline_config.py   # YAML Pipeline DSL
│           │   └── strategy/
│           │       ├── config.yaml              # 策略权重配置
│           │       └── config_loader.py         # 策略加载器
│           ├── tools/
│           │   ├── tool_client.py               # Java Tool Server HTTP 客户端
│           │   └── definitions.py               # LangChain @tool 工具定义
│           └── prompts/                         # Prompt 模板
│               ├── react-user.txt               # ReAct Agent 用户提示
│               ├── reviewagents/                # Agent 专用 System Prompt
│               │   ├── security-system.txt
│               │   ├── performance-system.txt
│               │   └── architecture-system.txt
│               └── pipeline/                    # Pipeline 专用 Prompt
│                   ├── diff-summary-*.txt
│                   ├── security-*.txt
│                   ├── logic-*.txt
│                   ├── quality-*.txt
│                   └── aggregation-*.txt
│
├── shared/
│   └── config/
│       └── review-config-template.yml   # 配置模板
├── docker-compose.yml                   # 5 服务容器编排
├── .github/workflows/ci.yml             # CI 流水线
└── LICENSE                              # MIT 许可证
```

---

## 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| **网关语言** | Java 21 | 六边形架构，CLI + Server 双模式 |
| **Agent 语言** | Python 3.11+ | FastAPI + LangChain 异步服务 |
| **CLI 框架** | Picocli 4.7 | 子命令式 CLI |
| **HTTP 服务** | Javalin 5.6 | 轻量 Webhook + Tool Server |
| **AI 框架** | LangChain 0.3+ | ReAct Agent + Tool Calling |
| **LLM 提供商** | OpenAI / Anthropic | 双 Provider 支持，可扩展 |
| **AST 解析** | JavaParser 3.26 | Java 语法树分析，SPI 多语言扩展 |
| **消息队列** | RabbitMQ 3.13 | 异步审查任务，死信队列 |
| **数据库** | MySQL 8.4 | 任务/结果持久化，HikariCP 连接池 |
| **向量存储** | Redis 7.2 / 内存 | Code RAG 向量检索 |
| **缓存** | Caffeine 3.1 | 两级审查结果缓存（内存 + 磁盘） |
| **弹性** | Resilience4j 2.2 | 熔断、限流、重试 |
| **可观测** | Micrometer + Prometheus | 审查指标采集与暴露 |
| **容器化** | Docker Compose | 5 服务一键部署 |
| **CI/CD** | GitHub Actions | 自动构建 + 测试 |

---

## 贡献指南

我们欢迎各种形式的贡献！

### 开发环境搭建

```bash
# Fork 并克隆项目
git clone https://github.com/YOUR_USERNAME/diffguard.git

# Java 网关开发
cd services/gateway
mvn clean verify    # 构建 + 运行全部测试

# Python Agent 开发
cd services/agent
pip install -e ".[dev]"
pytest              # 运行测试
```

### 贡献流程

1. Fork 本仓库
2. 创建特性分支（`git checkout -b feature/your-feature`）
3. 编写代码和测试
4. 确保所有测试通过（`mvn verify`）
5. 提交 Pull Request，描述变更内容和动机

### 扩展点

- **自定义 Agent** — 实现 `ReviewAgent` 基类，使用 `@AgentRegistry.register("name")` 注册
- **自定义 Pipeline 阶段** — 继承 `PipelineStage`，在 `pipeline-config.yaml` 中配置
- **自定义静态规则** — 实现 `StaticRule` 接口，注册到 `RuleEngine`
- **多语言 AST** — 实现 `LanguageASTProvider` SPI 接口
- **自定义 LLM Provider** — 实现 `LlmProvider` 接口

---

## License

本项目基于 [MIT License](LICENSE) 开源。

---

<p align="center">
  如果 DiffGuard 对你有帮助，欢迎给个 Star ⭐
</p>
