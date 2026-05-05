<p align="center">
  <img src="docs/images/logo.svg" alt="DiffGuard" width="120" height="120" />
</p>

<h1 align="center">DiffGuard</h1>

<p align="center">
  <strong>AI-Powered Multi-Agent Code Review System</strong>
</p>

<p align="center">
  English | <a href="./README_CN.md">中文</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange" alt="Java 21" />
  <img src="https://img.shields.io/badge/Python-3.11+-blue" alt="Python 3.11+" />
  <img src="https://img.shields.io/badge/LangChain-0.3+-green" alt="LangChain" />
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="MIT License" />
  <img src="https://img.shields.io/badge/PRs-Welcome-brightgreen" alt="PRs Welcome" />
</p>

---

## Overview

DiffGuard is an intelligent code review system that leverages **multi-agent collaboration**, **AST-aware code understanding**, and a **code knowledge graph** to deliver deep, precise, and automated code reviews.

Unlike conventional "diff + single LLM call" tools, DiffGuard decomposes the review into specialized dimensions — security, performance, and architecture — where independent AI agents work in parallel, share findings via a shared memory, and produce a consolidated verdict. A built-in static rule engine catches common issues at **zero LLM cost** before any model invocation.

### Why DiffGuard?

| Problem | DiffGuard's Approach |
|---------|----------------------|
| Manual code review is slow and inconsistent | AI-driven multi-dimensional review with structured output |
| Single LLM call misses deep issues | Parallel agents with cross-agent knowledge sharing |
| LLM lacks code context, causing false positives | 6 code analysis tools (AST / call graph / semantic search) |
| High LLM invocation cost | Static rule pre-filtering + two-tier cache + token budgets |
| Hard to integrate into existing workflows | Dual-mode: Git Hook (CLI) + GitHub Webhook (Server) |

---

## Features

### Three Review Modes

| Mode | Architecture | Use Case | Latency |
|------|-------------|----------|---------|
| **Simple** | Single LLM call | Quick checks, daily commits | Low |
| **Pipeline** | 3-stage pipeline (Summary → Parallel Review → Aggregation) | Medium-complexity changes | Medium |
| **Multi-Agent** | Strategy planning + parallel ReAct Agents + shared memory | Large PRs, high-risk changes | Higher |

### Specialized Agents

- **Security Agent** — SQL injection, XSS, command injection, hardcoded secrets, path traversal, SSRF, auth defects
- **Performance Agent** — N+1 queries, IO-bound loops, resource leaks, inefficient data structures
- **Architecture Agent** — Layer violations, responsibility mixing, circular dependencies, over-coupling

### Deep Code Understanding

- **AST Analysis** — JavaParser-based syntax tree extraction: method signatures, call edges, control flow, data flow
- **Code Knowledge Graph** — Cross-file dependency graph with impact analysis (BFS, max depth 3)
- **Code RAG** — Multi-granularity code slicing + vector retrieval (TF-IDF / OpenAI Embedding)
- **6 Agent Tools** — File content, diff context, method definition, call graph, related files, semantic search

### Production-Ready Infrastructure

- **Static Rule Engine** — Zero-cost regex-based detection of SQL injection, hardcoded secrets, dangerous functions, complexity
- **Two-Tier Cache** — In-memory (Caffeine) + disk persistence with SHA-256 keys and GZIP compression
- **Resilience** — Circuit breaker, rate limiter, exponential backoff retry, graceful degradation
- **Observability** — Micrometer metrics + Prometheus endpoint
- **Async Message Queue** — RabbitMQ task decoupling with dead-letter exchange

---

## Architecture

```
                        ┌─────────────────────────────────────────────┐
                        │           DiffGuard Architecture             │
                        └─────────────────────────────────────────────┘

  ┌──────────────┐                          ┌──────────────────────────┐
  │   Git Hook   │                          │   GitHub Webhook (PR)     │
  │ (pre-commit  │                          │  HMAC-SHA256 verification │
  │  /pre-push)  │                          │  IP rate limiting (30/min)│
  └──────┬───────┘                          └──────────┬───────────────┘
         │                                             │
         ▼                                             ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │                     Java Gateway (Javalin)                       │
  │  ┌────────────┐  ┌──────────────┐  ┌──────────────────────────┐ │
  │  │ CLI Entry  │  │   Webhook    │  │ Tool Server (port 9090)  │ │
  │  │ (Picocli)  │  │ (port 8080)  │  │ Session mgmt + routing   │ │
  │  └──────┬─────┘  └──────┬───────┘  └───────────▲──────────────┘ │
  │         │               │                       │                │
  │  ┌──────▼───────────────▼───────────────────────┴─────────────┐ │
  │  │                  Service Orchestration                      │ │
  │  │  DiffCollector → ASTEnricher → RuleEngine → ReviewEngine   │ │
  │  └─────────────────────────┬──────────────────────────────────┘ │
  │                            │                                    │
  │  ┌─────────────────────────▼──────────────────────────────────┐ │
  │  │                      Domain Layer                           │ │
  │  │  ┌──────────┐  ┌─────────────┐  ┌──────────┐              │ │
  │  │  │   AST    │  │  CodeGraph  │  │ Code RAG │              │ │
  │  │  │ Analysis │  │ Directed    │  │ TF-IDF / │              │ │
  │  │  │ SPI      │  │ Graph + BFS │  │ OpenAI   │              │ │
  │  │  └──────────┘  └─────────────┘  └──────────┘              │ │
  │  │  ┌──────────┐  ┌─────────────┐  ┌──────────┐              │ │
  │  │  │  Rules   │  │   Agent     │  │   LLM    │              │ │
  │  │  │  Engine  │  │   Toolset   │  │  Client  │              │ │
  │  │  │  (zero   │  │   (6 tools  │  │  Claude/ │              │ │
  │  │  │   LLM)   │  │   + sandbox)│  │  OpenAI  │              │ │
  │  │  └──────────┘  └─────────────┘  └──────────┘              │ │
  │  └────────────────────────────────────────────────────────────┘ │
  │  ┌────────────────────────────────────────────────────────────┐ │
  │  │                 Infrastructure Layer                        │ │
  │  │  LlmClient │ Resilience4j │ Cache │ Persistence │ Metrics  │ │
  │  │  RabbitMQ  │ MySQL        │ Redis │ HikariCP    │ Micrometer│ │
  │  └────────────────────────────────────────────────────────────┘ │
  └──────────────────────┬─────────────────────────────────────────┘
                         │ HTTP REST + RabbitMQ
                         ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │                 Python Agent Service (FastAPI)                    │
  │  ┌────────────────────────────────────────────────────────────┐ │
  │  │                   Orchestrator Layer                        │ │
  │  │  ┌──────────────────┐    ┌───────────────────────────────┐ │ │
  │  │  │  Pipeline Orch.  │    │  Multi-Agent Orchestrator     │ │ │
  │  │  │  Summary→Review→ │    │  Strategy→Parallel Agents→    │ │ │
  │  │  │  Aggregation     │    │  Deduplication                │ │ │
  │  │  └──────────────────┘    └───────────────────────────────┘ │ │
  │  └────────────────────────────────────────────────────────────┘ │
  │  ┌────────────────────────────────────────────────────────────┐ │
  │  │              Agent Layer (LangChain ReAct)                  │ │
  │  │  ┌──────────┐  ┌──────────────┐  ┌──────────────┐         │ │
  │  │  │ Security │  │ Performance  │  │ Architecture │         │ │
  │  │  │  Agent   │  │    Agent     │  │    Agent     │         │ │
  │  │  │ (w: 1.2) │  │  (w: 1.0)   │  │  (w: 1.0)   │         │ │
  │  │  └──────────┘  └──────────────┘  └──────────────┘         │ │
  │  │  Shared Memory (AgentMemory): Cross-agent knowledge sharing │ │
  │  │  Strategy Planner: Diff profiling → dynamic weight alloc.  │ │
  │  └────────────────────────────────────────────────────────────┘ │
  │  ┌────────────────────────────────────────────────────────────┐ │
  │  │  ToolClient ←→ Java Tool Server (HTTP, session-scoped)     │ │
  │  └────────────────────────────────────────────────────────────┘ │
  └──────────────────────────────────────────────────────────────────┘
```

---

## Quick Start

### Prerequisites

- **Java 21** (Eclipse Temurin recommended)
- **Python 3.11+**
- **Git**
- **LLM API Key** (OpenAI or Anthropic)

### 30-Second CLI Experience

```bash
# 1. Clone the repository
git clone https://github.com/kunxing/diffguard.git
cd diffguard

# 2. Build the gateway
cd services/gateway && mvn clean package -DskipTests && cd ../..

# 3. Set your API key
export DIFFGUARD_API_KEY="sk-your-api-key-here"

# 4. Run a review in any Git project
java -jar services/gateway/target/diffguard-1.0.0.jar review --staged
```

---

## Installation

### Option 1: Local CLI Installation

```bash
# Build the fat JAR
cd services/gateway
mvn clean package

# Install Git hooks (pre-commit + pre-push)
java -jar target/diffguard-1.0.0.jar install

# Uninstall hooks
java -jar target/diffguard-1.0.0.jar uninstall
```

After installation, every `git commit` or `git push` will automatically trigger a code review. Commits are blocked when critical issues are found.

### Option 2: Docker Compose Deployment (Server Mode)

```bash
# Configure environment variables
export DIFFGUARD_API_KEY="sk-your-api-key"
export DIFFGUARD_WEBHOOK_SECRET="your-webhook-secret"
export DIFFGUARD_GITHUB_TOKEN="ghp-your-token"

# Start all services
docker compose up -d

# Check service status
docker compose ps
```

Endpoints after startup:

| Service | URL |
|---------|-----|
| Webhook endpoint | `http://localhost:8080/webhook/github` |
| Tool Server | `http://localhost:9090` |
| Agent health check | `http://localhost:8000/api/v1/health` |
| RabbitMQ Management | `http://localhost:15672` |
| Prometheus metrics | `http://localhost:9091/metrics` |

---

## Configuration

DiffGuard uses a **three-layer configuration merge** strategy: built-in defaults → project-level `application.yml` → user home directory override.

Core configuration (full template at [review-config-template.yml](shared/config/review-config-template.yml)):

```yaml
# LLM settings
llm:
  provider: openai                          # openai | claude
  model: claude-haiku-4-5-20251001
  maxTokens: 16384
  temperature: 0.3
  timeout: 240
  apiKeyEnv: DIFFGUARD_API_KEY              # Reads from env var, never stores plaintext
  baseUrl: ""                               # Custom API endpoint (supports proxies)

# Rule configuration
rules:
  enabled: [security, bug-risk, code-style, performance]
  threshold: info

# Review options
review:
  maxDiffFiles: 20                          # Max files per review
  maxTokensPerFile: 4000                    # Max tokens per file
  language: en                              # Output language
  pipelineMode: false                       # Enable Pipeline mode
  multiAgentMode: false                     # Enable Multi-Agent mode

# Webhook server (Server mode)
webhook:
  port: 8080
  secretEnv: DIFFGUARD_WEBHOOK_SECRET
  githubTokenEnv: DIFFGUARD_GITHUB_TOKEN
  repoMappings:
    "owner/repo": "/path/to/local/repo"
```

### Environment Variables

| Variable | Purpose | Required |
|----------|---------|----------|
| `DIFFGUARD_API_KEY` | LLM API key | Yes |
| `DIFFGUARD_API_BASE_URL` | Custom API endpoint | No |
| `DIFFGUARD_WEBHOOK_SECRET` | GitHub Webhook signing secret | Server mode |
| `DIFFGUARD_GITHUB_TOKEN` | GitHub API Token (for PR comments) | Server mode |

### Agent Strategy Configuration

The strategy planner dynamically adjusts agent weights based on file type and risk level (`agent/strategy/config.yaml`):

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
    focus_areas: ["input validation", "access control"]
```

---

## Usage

### CLI Commands

```bash
# Review staged changes (pre-commit scenario)
java -jar diffguard.jar review --staged

# Review diff between two refs
java -jar diffguard.jar review --from main --to feature/login

# Pipeline mode (3-stage specialized review)
java -jar diffguard.jar review --staged --pipeline

# Multi-Agent mode (parallel agent review)
java -jar diffguard.jar review --staged --multi-agent

# Force pass (ignore CRITICAL issues)
java -jar diffguard.jar review --staged --force

# Custom config file
java -jar diffguard.jar review --staged --config /path/to/config.yml
```

### Example Output

```
╔══════════════════════════════════════════════════════════════════╗
║  DiffGuard Code Review Report                                   ║
╠══════════════════════════════════════════════════════════════════╣
║                                                                  ║
║  Summary: 3 files reviewed, 5 issues found                      ║
║                                                                  ║
║  [CRITICAL] UserService.java:42                                  ║
║  Type: SQL Injection                                             ║
║  Message: String concatenation in SQL query — injection risk     ║
║  Suggestion: Use PreparedStatement instead of concatenation      ║
║                                                                  ║
║  [WARNING] OrderController.java:78                               ║
║  Type: Missing Authorization                                     ║
║  Message: Delete endpoint lacks permission verification          ║
║  Suggestion: Add @PreAuthorize("hasRole('ADMIN')") annotation    ║
║                                                                  ║
╚══════════════════════════════════════════════════════════════════╝
```

### GitHub Webhook PR Comments

In server mode, DiffGuard automatically posts formatted Markdown review comments on pull requests, including severity labels, code locations, and fix suggestions.

---

## Core Workflow

### Pipeline Mode

```
Diff Input
    │
    ▼
┌──────────────┐
│ SummaryStage │  Structured output: change summary, file list,
│ (LLM output) │  change types, risk rating (1-5)
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
│       │  Parallel   │         │      │
└───────┼────────────┼─────────┼──────┘
        │            │         │
        ▼            ▼         ▼
┌──────────────────────────────────────┐
│        AggregationStage              │
│  Deduplication + highest severity +  │
│  comprehensive summary               │
└──────────────┬───────────────────────┘
               │
               ▼
        Structured Review Report
```

### Multi-Agent Mode

```
Diff Input
    │
    ▼
┌─────────────────────┐
│   StrategyPlanner   │  File classification + risk assessment +
│   Diff Profiling    │  weight computation → which agents to enable
└─────────┬───────────┘
          │
          ▼
┌─────────────────────────────────────────────┐
│       Parallel Agent Execution (asyncio)     │
│                                               │
│  ┌────────────┐ ┌──────────────┐ ┌──────────┐│
│  │  Security  │ │ Performance  │ │Architecture│
│  │   Agent    │ │    Agent     │ │   Agent    ││
│  │  (ReAct)   │ │   (ReAct)    │ │  (ReAct)   ││
│  │  w: 1.2    │ │   w: 1.0    │ │   w: 1.0  ││
│  └─────┬──────┘ └──────┬───────┘ └─────┬─────┘│
│        │               │               │       │
│        └───────┬───────┴───────┬───────┘       │
│        ┌───────▼───────────────▼───────┐       │
│        │        AgentMemory            │       │
│        │  Cross-agent knowledge sharing│       │
│        │  Later agents see prior finds │       │
│        └───────────────────────────────┘       │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────┐
│           Result Aggregation          │
│  Deduplicate by file:line:type       │
│  Merge has_critical flag             │
└──────────────┬───────────────────────┘
               │
               ▼
        Structured Review Report
```

---

## Project Structure

```
DiffGuard/
├── services/
│   ├── gateway/                          # Java Gateway Service
│   │   ├── pom.xml                       # Maven build (Java 21, 18 dependencies)
│   │   ├── Dockerfile                    # Based on eclipse-temurin:21-jre
│   │   └── src/main/java/com/diffguard/
│   │       ├── DiffGuard.java            # Application entry point
│   │       ├── cli/                      # CLI commands (Picocli)
│   │       │   ├── DiffGuardMain.java    # Top-level command
│   │       │   ├── ReviewCommand.java    # Review command
│   │       │   ├── InstallCommand.java   # Git hook installer
│   │       │   ├── ServerCommand.java    # Webhook server
│   │       │   └── UninstallCommand.java # Hook uninstaller
│   │       ├── adapter/                  # Adapter layer
│   │       │   ├── webhook/              # GitHub Webhook integration
│   │       │   │   ├── WebhookServer.java       # Javalin HTTP server
│   │       │   │   ├── WebhookController.java   # Request handler
│   │       │   │   ├── SignatureVerifier.java   # HMAC-SHA256 verification
│   │       │   │   ├── RateLimiter.java         # IP rate limiting (Caffeine)
│   │       │   │   ├── GitHubPayloadParser.java # PR payload parser
│   │       │   │   └── GitHubApiClient.java     # GitHub API client
│   │       │   └── toolserver/           # Agent tool server
│   │       │       ├── ToolServerController.java  # Tool routing
│   │       │       └── ToolSessionManager.java    # Session management (10min TTL)
│   │       ├── domain/                   # Domain layer
│   │       │   ├── review/              # Review engine
│   │       │   │   ├── ReviewEngine.java         # Unified review interface
│   │       │   │   ├── ReviewService.java        # Simple mode implementation
│   │       │   │   ├── AsyncReviewEngine.java    # Async polling engine
│   │       │   │   ├── ReviewCache.java          # Two-tier cache
│   │       │   │   └── model/                    # ReviewResult/Issue/Severity
│   │       │   ├── agent/               # Agent tool system
│   │       │   │   ├── core/            # AgentContext/AgentTool/ToolResult
│   │       │   │   ├── tools/           # 6 tool implementations + security sandbox
│   │       │   │   ├── python/          # Python Agent HTTP client
│   │       │   │   └── ToolRegistry.java
│   │       │   ├── ast/                 # AST semantic analysis
│   │       │   │   ├── ASTAnalyzer.java         # JavaParser single-file analysis
│   │       │   │   ├── ASTEnricher.java         # Diff AST context enrichment
│   │       │   │   ├── ProjectASTAnalyzer.java  # Cross-file relationship builder
│   │       │   │   ├── ASTContextBuilder.java    # Token budget controller
│   │       │   │   ├── ASTCache.java            # Caffeine cache
│   │       │   │   ├── spi/                     # Multi-language AST SPI
│   │       │   │   └── model/                   # Data models
│   │       │   ├── codegraph/           # Code knowledge graph
│   │       │   │   ├── CodeGraph.java           # Directed graph + query API
│   │       │   │   ├── CodeGraphBuilder.java    # 4-pass builder
│   │       │   │   ├── GraphNode.java           # FILE/CLASS/METHOD/INTERFACE
│   │       │   │   └── GraphEdge.java           # CALLS/EXTENDS/IMPLEMENTS/IMPORTS
│   │       │   ├── coderag/             # Code semantic retrieval
│   │       │   │   ├── CodeRAGService.java      # Index + search facade
│   │       │   │   ├── CodeSlicer.java          # Multi-granularity slicer
│   │       │   │   ├── LocalTFIDFProvider.java  # TF-IDF embedding (zero dep)
│   │       │   │   ├── OpenAiEmbeddingProvider.java  # OpenAI embedding
│   │       │   │   ├── InMemoryVectorStore.java      # In-memory vector store
│   │       │   │   └── RedisVectorStore.java         # Redis vector store
│   │       │   └── rules/               # Static rule engine
│   │       │       └── RuleEngine.java          # 4 zero-LLM-cost rules
│   │       ├── service/                 # Application service layer
│   │       │   ├── ReviewApplicationService.java  # CLI orchestration
│   │       │   ├── ReviewOrchestrator.java        # Server orchestration (10-step pipeline)
│   │       │   └── ReviewEngineFactory.java       # Engine factory
│   │       ├── infrastructure/          # Infrastructure layer
│   │       │   ├── llm/                # LLM client
│   │       │   │   ├── LlmClient.java           # Retry + batch + format correction
│   │       │   │   ├── provider/                # Claude/OpenAI HTTP providers
│   │       │   │   └── BatchReviewExecutor.java # Concurrent batch (max 3)
│   │       │   ├── messaging/          # RabbitMQ message queue
│   │       │   ├── persistence/        # MySQL persistence (HikariCP)
│   │       │   ├── prompt/             # Prompt template engine
│   │       │   ├── resilience/         # Resilience4j resilience service
│   │       │   ├── config/             # Three-layer config loader
│   │       │   ├── git/                # JGit diff collection
│   │       │   ├── observability/      # Micrometer + Prometheus
│   │       │   └── output/             # Terminal UI (ANSI/Spinner/Markdown)
│   │       └── resources/
│   │           ├── application.yml      # Default configuration
│   │           └── db/schema.sql        # Database schema
│   │
│   └── agent/                            # Python Agent Service
│       ├── pyproject.toml               # Dependency management (hatchling)
│       ├── Dockerfile                   # Based on python:3.12-slim
│       └── diffguard/
│           ├── main.py                  # FastAPI entry (HTTP + RabbitMQ)
│           ├── config.py                # Environment variable config
│           ├── models/schemas.py        # Pydantic data models
│           ├── messaging/
│           │   └── rabbitmq_consumer.py # Async message consumer (aio-pika)
│           ├── agent/
│           │   ├── base.py              # ReviewAgent abstract base class
│           │   ├── registry.py          # Decorator-based Agent Registry
│           │   ├── memory.py            # Cross-agent shared memory
│           │   ├── strategy_planner.py  # Diff profiling + strategy planning
│           │   ├── multi_agent_orchestrator.py   # Multi-Agent parallel orchestrator
│           │   ├── pipeline_orchestrator.py      # Pipeline sequential orchestrator
│           │   ├── builtin_agents/              # Built-in agent implementations
│           │   │   ├── security.py              # Security review agent (ReAct)
│           │   │   ├── performance.py           # Performance review agent (ReAct)
│           │   │   └── architecture.py          # Architecture review agent (ReAct)
│           │   ├── pipeline/                    # Pipeline stages
│           │   │   └── stages/
│           │   │       ├── summary.py           # Diff summary (structured output)
│           │   │       ├── reviewer.py          # Parallel reviewers (ReAct)
│           │   │       ├── aggregation.py       # Result aggregation
│           │   │       ├── static_rules.py      # Static rules (zero LLM)
│           │   │       └── pipeline_config.py   # YAML Pipeline DSL
│           │   └── strategy/
│           │       ├── config.yaml              # Strategy weight config
│           │       └── config_loader.py         # Strategy config loader
│           ├── tools/
│           │   ├── tool_client.py               # Java Tool Server HTTP client
│           │   └── definitions.py               # LangChain @tool definitions
│           └── prompts/                         # Prompt templates
│               ├── react-user.txt               # Shared ReAct user prompt
│               ├── reviewagents/                # Agent system prompts
│               │   ├── security-system.txt
│               │   ├── performance-system.txt
│               │   └── architecture-system.txt
│               └── pipeline/                    # Pipeline prompts
│                   ├── diff-summary-*.txt
│                   ├── security-*.txt
│                   ├── logic-*.txt
│                   ├── quality-*.txt
│                   └── aggregation-*.txt
│
├── shared/
│   └── config/
│       └── review-config-template.yml   # Configuration template
├── docker-compose.yml                   # 5-service orchestration
├── .github/workflows/ci.yml             # CI pipeline
└── LICENSE                              # MIT License
```

---

## Tech Stack

| Layer | Technology | Description |
|-------|-----------|-------------|
| **Gateway** | Java 21 | Hexagonal architecture, CLI + Server dual mode |
| **Agent Service** | Python 3.11+ | FastAPI + LangChain async service |
| **CLI Framework** | Picocli 4.7 | Subcommand-style CLI |
| **HTTP Server** | Javalin 5.6 | Lightweight webhook + tool server |
| **AI Framework** | LangChain 0.3+ | ReAct Agent + Tool Calling |
| **LLM Providers** | OpenAI / Anthropic | Dual-provider support, extensible |
| **AST Parsing** | JavaParser 3.26 | Java syntax tree analysis with SPI for multi-language |
| **Message Queue** | RabbitMQ 3.13 | Async review tasks with dead-letter exchange |
| **Database** | MySQL 8.4 | Task/result persistence with HikariCP connection pool |
| **Vector Store** | Redis 7.2 / In-memory | Code RAG vector retrieval |
| **Cache** | Caffeine 3.1 | Two-tier review result cache (memory + disk) |
| **Resilience** | Resilience4j 2.2 | Circuit breaker, rate limiting, retry |
| **Observability** | Micrometer + Prometheus | Review metrics collection and exposure |
| **Containerization** | Docker Compose | 5-service one-command deployment |
| **CI/CD** | GitHub Actions | Automated build + test |

---

## Contributing

Contributions are welcome in all forms!

### Development Setup

```bash
# Fork and clone
git clone https://github.com/YOUR_USERNAME/diffguard.git

# Java gateway development
cd services/gateway
mvn clean verify    # Build + run all tests

# Python agent development
cd services/agent
pip install -e ".[dev]"
pytest              # Run tests
```

### Contribution Workflow

1. Fork this repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Write code and tests
4. Ensure all tests pass (`mvn verify`)
5. Submit a Pull Request describing your changes and motivation

### Extension Points

- **Custom Agent** — Extend `ReviewAgent`, register with `@AgentRegistry.register("name")`
- **Custom Pipeline Stage** — Extend `PipelineStage`, configure in `pipeline-config.yaml`
- **Custom Static Rule** — Implement the `StaticRule` interface, register with `RuleEngine`
- **Multi-language AST** — Implement the `LanguageASTProvider` SPI interface
- **Custom LLM Provider** — Implement the `LlmProvider` interface

---

## License

This project is licensed under the [MIT License](LICENSE).

---

<p align="center">
  If DiffGuard helps your workflow, consider giving it a star ⭐
</p>
