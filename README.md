

<h1 align="center">DiffGuard</h1>

<p align="center">
  <strong>AI 驱动的分层智能代码审查系统</strong>
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

DiffGuard 是一个面向开发团队的 AI 代码审查系统，通过 **分层审查编排**、**AST 语义理解** 和 **代码知识图谱** 提供深度、精准的自动化 Code Review。

与传统的 LLM 单次调用审查不同，DiffGuard 将代码审查拆解为多个专业维度——安全、逻辑、质量，由并行域审查器协同分析后聚合结论。同时，内置的静态规则引擎可在 **零 LLM 开销** 下拦截常见问题。

### 为什么选择 DiffGuard？

| 痛点 | DiffGuard 的解决方案 |
|------|----------------------|
| 人工 Code Review 耗时且标准不一 | AI 多维度自动审查，输出结构化结果 |
| 单次 LLM 调用容易遗漏深层问题 | 并行域审查 + 共享上下文，交叉验证 |
| LLM 缺乏代码上下文导致误报 | 6 种代码上下文工具（AST / 调用链 / 语义搜索） |
| AI 调用成本高 | 静态规则前置过滤 + 两级缓存 + Token 预算控制 |
| 难以集成到现有工作流 | Git Hook + GitHub Action 接入 |

---

## 核心特性

### 三种审查模式

| 模式 | 架构 | 适用场景 | 延迟 |
|------|------|----------|------|
| **Simple** | 单次 LLM 调用 | 快速检查、日常提交 | 低 |
| **Pipeline** | 3 阶段流水线（摘要 → 并行审查 → 聚合） | 中等复杂度变更 | 中 |
| **Multi-Agent** | 兼容入口（当前回退到 Pipeline） | 大型 PR、高风险变更 | 较高 |

> 说明：当前 `services/agent/src` 主实现是 `Pipeline`；`MULTI_AGENT` 仅保留为兼容入口，与 `PIPELINE` 共用执行链路。

### 审查器系统（当前实现）

- **Security Reviewer** — SQL 注入、XSS、命令注入、硬编码密钥、路径遍历、SSRF、认证缺陷
- **Logic Reviewer** — 空指针风险、并发问题、资源泄漏、边界条件与流程错误
- **Quality Reviewer** — 代码可维护性、复杂度、风格一致性、可读性改进

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

## 微服务重构进展（2026-05-23）

当前 Java 侧正按“先 Tool，再 Orchestrator”的节奏做增量微服务化改造：  
已完成 gateway 远程适配与旧编排下沉去重，当前阻塞点是 Python Agent 的真实 MQ 联调验收。

- 进度总览：[PROGRESS.md](PROGRESS.md)
- 当前架构（As-Is）：[ARCHITECTURE_CURRENT.md](ARCHITECTURE_CURRENT.md)
- 目标架构（To-Be）：[ARCHITECTURE_TARGET.md](ARCHITECTURE_TARGET.md)
- 迁移计划（Day1~Day5）：[MIGRATION_PLAN.md](MIGRATION_PLAN.md)
- Orchestrator 契约：[ORCHESTRATOR_CONTRACT.md](ORCHESTRATOR_CONTRACT.md)
- 第一轮验收记录：[REFACTOR_ACCEPTANCE_ROUND1.md](REFACTOR_ACCEPTANCE_ROUND1.md)

---

## 架构设计

```
                        ┌─────────────────────────────────────────────┐
                        │              DiffGuard 整体架构               │
                        └─────────────────────────────────────────────┘

  ┌──────────────┐
  │   Git Hook   │
  │ (pre-commit  │
  │  /pre-push)  │
  └──────┬───────┘
         │
         ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │                     Java Gateway (Javalin)                       │
  │  ┌────────────┐                     ┌──────────────────────────┐ │
  │  │ CLI 入口    │                     │ Tool Server (端口 9090)  │ │
  │  │ (Picocli)   │                     │ 会话管理 + 工具路由      │ │
  │  └──────┬─────┘                     └───────────▲──────────────┘ │
  │         │                                       │                │
  │  ┌──────▼───────────────────────────────────────┴─────────────┐ │
  │  │                    服务调用层                                │ │
  │  │  CLI: DiffCollector → ASTEnricher → ReviewExecutionAdapter │ │
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
  │  │  RabbitMQ Publisher      │ 本地内存结果态 │ 指标监控 │ │
  │  └────────────────────────────────────────────────────────────┘ │
  └──────────────────────┬─────────────────────────────────────────┘
                         │ HTTP REST + RabbitMQ
                         ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │                   Python Agent Service (FastAPI)                 │
  │  ┌────────────────────────────────────────────────────────────┐ │
  │  │                    编排器层                                  │ │
  │  │  ┌──────────────────┐    ┌───────────────────────────────┐ │ │
  │  │  │ PipelineOrchestrator│  │ MultiAgentOrchestrator(路线图)│ │ │
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
  │  │  共享记忆 (AgentMemory): 跨 Agent 知识共享（路线图）            │ │
  │  │  策略规划器 (StrategyPlanner): 基于 Diff 特征动态分配权重（路线图）│ │
  │  └────────────────────────────────────────────────────────────┘ │
  │  ┌────────────────────────────────────────────────────────────┐ │
  │  │  ToolClient ←→ Java Tool Server (HTTP, 会话隔离)            │ │
  │  └────────────────────────────────────────────────────────────┘ │
  └──────────────────────────────────────────────────────────────────┘
```

> 当前运行链路说明：Python Agent 生产主链为 `PipelineOrchestrator`。图中 `MultiAgentOrchestrator / AgentMemory / StrategyPlanner` 为路线图能力。

---

## 快速开始

### 环境要求

- **Java 21**（Temurin 发行版推荐）
- **Maven 3.9+**
- **Python 3.11+**（仅 Agent 服务需要）
- **Git**
- **LLM API Key**（OpenAI 或 Anthropic，也支持兼容接口）

### 30 秒体验（CLI 模式）

```bash
# 1. 克隆项目
git clone https://github.com/kunxing/diffguard.git
cd diffguard

# 2. 构建网关
cd services/gateway && mvn clean package -DskipTests && cd ../..

# 3. 设置环境变量
## Linux / macOS
export DIFFGUARD_API_KEY="your-api-key-here"
export DIFFGUARD_API_BASE_URL="https://your-api-endpoint/v1"   # 可选，自定义 API 端点

## Windows PowerShell
$env:DIFFGUARD_API_KEY = "your-api-key-here"
$env:DIFFGUARD_API_BASE_URL = "https://your-api-endpoint/v1"   # 可选，自定义 API 端点

# 4. 在任意 Git 项目中执行审查
java -jar services/gateway/target/diffguard-1.0.0.jar review --pr owner/repo#123
```

> **提示：** 如果使用自定义 API 端点（代理/转发），代码会在 `DIFFGUARD_API_BASE_URL` 后拼接 `/messages`，请确保地址正确。例如 Anthropic 兼容接口应设为 `https://your-proxy.com/anthropic/v1`。

---

## 安装步骤

### 方式一：本地 CLI 安装

```bash
# 构建 fat JAR
cd services/gateway
mvn clean package

# 设置环境变量（以 PowerShell 为例）
$env:DIFFGUARD_API_KEY = "your-api-key"
$env:DIFFGUARD_API_BASE_URL = "https://your-api-endpoint/v1"  # 可选

# 审查指定 PR
java -jar target/diffguard-1.0.0.jar review --pr owner/repo#123

# 审查另一个 PR
java -jar target/diffguard-1.0.0.jar review --pr owner/repo#123

# Pipeline 多维度审查
java -jar target/diffguard-1.0.0.jar review --pr owner/repo#123 --pipeline

# Multi-Agent 兼容入口（当前与 Pipeline 同链路）
java -jar target/diffguard-1.0.0.jar review --pr owner/repo#123 --multi-agent

# 有严重问题也强制通过
java -jar target/diffguard-1.0.0.jar review --pr owner/repo#123 --force

# 安装 Git Hook（pre-commit + pre-push，自动审查）
java -jar target/diffguard-1.0.0.jar install

# 卸载 Hook
java -jar target/diffguard-1.0.0.jar uninstall

# 启动独立 Tool 服务（微服务拆分场景）
java -jar target/diffguard-1.0.0.jar tool-server --port 9090

# 启动独立 Review Orchestrator 服务（第二阶段）
java -jar target/diffguard-1.0.0.jar orchestrator-server --port 8088
```

安装 Git Hook 后，每次 `git commit` 或 `git push` 将自动触发代码审查。
  
> Hook 仅支持 PR 模式。请提前设置 `DIFFGUARD_PR=owner/repo#number`，未设置时 Hook 会跳过审查。

### 方式二：Docker Compose 部署（Action-only 配套服务）

```bash
# 配置环境变量
export DIFFGUARD_API_KEY="sk-your-api-key"
export DIFFGUARD_TOOL_SECRET="your-tool-secret"  # 可选，开启后 Tool API 需携带 X-Tool-Secret

# 一键启动所有服务
docker compose up -d

# 查看服务状态
docker compose ps
```

启动后可访问：
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
  provider: claude                          # openai | claude
  model: mimo-v2-pro                        # 模型名称，根据你的 API 提供商选择
  max_tokens: 16384
  temperature: 0.3
  timeout_seconds: 240
  api_key_env: DIFFGUARD_API_KEY            # 从环境变量读取，不存储明文
  base_url_env: DIFFGUARD_API_BASE_URL      # 自定义 API 端点（支持代理）

# 规则配置
rules:
  enabled: [security, bug-risk, code-style, performance]
  severity_threshold: info

# 审查选项
review:
  max_diff_files: 20                        # 单次审查最大文件数
  max_tokens_per_file: 4000                 # 单文件最大 Token 数
  language: zh                              # 输出语言

# Tool 服务（可独立部署）
# tool_service:
#   embedded: true          # true: server 进程内嵌 Tool；false: 需单独运行 tool-server 命令
#   host: localhost
#   port: 9090
#   # url: http://localhost:9090

# Orchestrator 远程模式（gateway 调 orchestrator-service）
# orchestrator:
#   mode: legacy            # legacy | remote
#   url: http://localhost:8088
#   timeout_seconds: 360
#   poll_interval_ms: 1000
#   fallback_to_legacy: true
```

### 环境变量

| 变量名 | 用途 | 必需 |
|--------|------|------|
| `DIFFGUARD_API_KEY` | LLM API Key | 是 |
| `DIFFGUARD_API_BASE_URL` | 自定义 API 端点（代码会在此地址后拼接 `/messages`） | 使用代理时必需 |

> **Windows 用户：** 可通过系统设置将 `DIFFGUARD_API_KEY` 和 `DIFFGUARD_API_BASE_URL` 添加为用户环境变量，免去每次手动设置。也可将 JDK 的 `bin` 目录加入系统 PATH，简化 `java` 命令调用。

### Agent 策略配置

策略规划器配置用于 Multi-Agent 路线图能力，当前 Pipeline 主链不依赖它（`agent/strategy/config.yaml`）：

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
# 审查指定 PR
java -jar diffguard.jar review --pr owner/repo#123

# 审查另一个 PR
java -jar diffguard.jar review --pr owner/repo#123

# Pipeline 模式
java -jar diffguard.jar review --pr owner/repo#123 --pipeline

# Multi-Agent 兼容入口（当前与 Pipeline 同链路）
java -jar diffguard.jar review --pr owner/repo#123 --multi-agent

# 忽略严重问题，强制通过
java -jar diffguard.jar review --pr owner/repo#123 --force

# 指定配置文件
java -jar diffguard.jar review --pr owner/repo#123 --config /path/to/config.yml
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

### Multi-Agent 路线图（当前未独立落地）

> 当前 Python Agent 主实现是 Pipeline；下图保留为目标形态参考。

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
│   │       ├── cli/
│   │       ├── cli/                      # CLI 命令层（Picocli）
│   │       │   ├── DiffGuardMain.java    # 主命令（review/install/uninstall/tool-server/orchestrator-server）
│   │       │   ├── ReviewCommand.java    # 审查命令
│   │       │   ├── InstallCommand.java   # Git Hook 安装
│   │       │   └── UninstallCommand.java # Hook 卸载
│   │       ├── toolserver/              # Tool Server（会话 + 工具路由）
│   │       ├── orchestrator/            # 独立 orchestrator API（任务/状态/结果）
│   │       ├── review/                  # 审查核心（引擎、编排、模型、AST、规则、RAG）
│   │       │   ├── ReviewApplicationService.java # CLI 编排入口
│   │       │   ├── ReviewExecutionAdapter.java   # remote/legacy 适配
│   │       │   ├── ReviewEngineFactory.java      # 引擎工厂
│   │       │   ├── model/                        # ReviewResult/Issue/Severity
│   │       │   ├── ast/                          # AST 分析与增强
│   │       │   ├── rules/                        # 静态规则引擎
│   │       │   ├── codegraph/                    # 代码知识图谱
│   │       │   └── coderag/                      # 语义检索
│   │       ├── agent/                   # Agent 工具体系（core/tools/python）
│   │       ├── platform/                # 平台能力（config/git/llm/messaging/...）
│   │       │   ├── config/
│   │       │   ├── git/
│   │       │   ├── llm/
│   │       │   ├── messaging/
│   │       │   ├── output/
│   │       │   └── resilience/
│   │       └── resources/
│   │           ├── application.yml      # 默认配置
│   │           └── db/schema.sql        # 数据库建表语句
│   │
│   └── agent/                            # Python Agent 服务
│       ├── pyproject.toml               # 依赖管理（hatchling）
│       ├── Dockerfile                   # 基于 python:3.12-slim
│       ├── src/diffguard_agent/
│       │   ├── main.py                  # FastAPI 入口（/api/v1/review, /webhook-review）
│       │   ├── config.py                # 环境变量配置
│       │   ├── models/schemas.py        # Pydantic 数据模型
│       │   ├── metrics.py               # 审查指标追踪
│       │   ├── github/                  # GitHub API 客户端与评论构建
│       │   ├── utils/                   # diff 切分工具
│       │   ├── agent/
│       │   │   ├── pipeline_orchestrator.py      # Pipeline 编排 + 自动分片
│       │   │   ├── llm_utils.py                  # LLM 工厂 + 重试
│       │   │   ├── false_positive_filter.py      # 两阶段误报过滤
│       │   │   ├── diff_parser.py                # Diff 行号映射器
│       │   │   └── pipeline/
│       │   │       ├── pipeline-config.yaml      # Pipeline 配置
│       │   │       ├── pipeline_config.py        # YAML DSL 解析
│       │   │       └── stages/
│       │   │           ├── summary.py            # 阶段1：摘要
│       │   │           ├── reviewer.py           # 阶段2：并行域审查
│       │   │           ├── aggregation.py        # 阶段3：聚合
│       │   │           ├── fp_filter_stage.py    # 阶段4：误报过滤
│       │   │           └── static_rules.py       # 可选静态规则阶段
│       │   ├── llm/prompts/pipeline/             # Pipeline Prompt 模板
│       │   └── tools/                            # Tool 客户端包装（当前仓库存在迁移残留）
│       └── tests/                                # pytest 测试
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
| **网关语言** | Java 21 | 六边形架构，CLI + Tool/Orchestrator 服务能力 |
| **Agent 语言** | Python 3.11+ | FastAPI + LangChain 异步服务 |
| **CLI 框架** | Picocli 4.7 | 子命令式 CLI |
| **HTTP 服务** | Javalin 5.6 | 轻量 Tool Server + Orchestrator API |
| **AI 框架** | LangChain 0.3+ | ReAct Agent + Tool Calling |
| **LLM 提供商** | OpenAI / Anthropic | 双 Provider 支持，可扩展 |
| **AST 解析** | JavaParser 3.26 | Java 语法树分析，SPI 多语言扩展 |
| **消息队列** | RabbitMQ 3.13 | 异步审查任务，死信队列 |
| **向量存储** | 内存 | Code RAG 向量检索 |
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

- **自定义 Pipeline 阶段** — 继承 `PipelineStage`，在 `pipeline-config.yaml` 中配置
- **自定义多 Agent 能力** — 目前为路线图，需先补齐 `MultiAgentOrchestrator` / `StrategyPlanner`
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

