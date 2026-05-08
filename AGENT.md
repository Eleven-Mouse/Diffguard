# DiffGuard Agent Architecture Specification

> 本文档为 DiffGuard AI Agent 系统的架构约束规范。所有涉及 Agent 模块的设计、开发、测试行为必须遵守本文档。
> 违反本文档的代码必须在 Code Review 阶段被拒绝。

---

## 1. 项目架构总览

### 1.1 系统定位

DiffGuard 是 AI 驱动的多维度代码审查系统。核心能力：通过专业化 Agent 并行审查代码的 安全 / 性能 / 架构 维度，聚合输出结构化审查结论。

### 1.2 双服务架构

```
┌─────────────────────────────────────────────────────────┐
│                    DiffGuard Gateway                     │
│                Java 21 / Javalin / Picocli               │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐ │
│  │  CLI入口  │  │ Webhook  │  │ Tool     │  │ Static  │ │
│  │ (review) │  │ Adapter  │  │ Server   │  │ Rules   │ │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └─────────┘ │
│       │              │              │                     │
│  ┌────▼──────────────▼──────────────▼──────────────────┐ │
│  │              ReviewOrchestrator                      │ │
│  │  DiffCollect → AST → Rules → Engine → Persist       │ │
│  └────────────────────┬────────────────────────────────┘ │
│                       │                                   │
│  ┌────────────────────▼────────────────────────────────┐ │
│  │           ReviewEngineFactory                       │ │
│  │  SIMPLE → LlmClient (Java直连LLM)                  │ │
│  │  PIPELINE / MULTI_AGENT → PythonReviewEngine        │ │
│  └────────────────────┬────────────────────────────────┘ │
│         HTTP POST     │  RabbitMQ (async)                │
└───────────────────────┼──────────────────────────────────┘
                        │
           HTTP /api/v1/review
                        │
┌───────────────────────▼──────────────────────────────────┐
│                   DiffGuard Agent                         │
│              Python 3.11+ / FastAPI / LangChain            │
│                                                           │
│  ┌─────────────────┐  ┌──────────────────────────────┐   │
│  │ Pipeline Mode    │  │ Multi-Agent Mode              │   │
│  │ Summary → Review │  │ StrategyPlanner               │   │
│  │ → Aggregation    │  │   ↓                           │   │
│  └─────────────────┘  │ ┌───────────┐ ┌────────────┐ │   │
│                        │ │ Security  │ │Performance │ │   │
│                        │ │  Agent    │ │  Agent     │ │   │
│                        │ └───────────┘ └────────────┘ │   │
│                        │ ┌────────────────────────────┐│   │
│                        │ │    Architecture Agent      ││   │
│                        │ └────────────────────────────┘│   │
│                        │   ↓ AgentMemory (cross-agent) │   │
│                        │   ↓ Result Aggregation        │   │
│                        └──────────────────────────────┘   │
│                        │                                   │
│           HTTP /api/v1/tools/* (回调)                     │
└────────────────────────┼──────────────────────────────────┘
                         │
              Java Tool Server (port 9090)
              file-content / diff-context / method-definition
              call-graph / related-files / semantic-search
```

### 1.3 调用链路约束

| 链路 | 协议 | 约束 |
|------|------|------|
| Java → Python 审查请求 | HTTP POST `/api/v1/review` | 超时 300s，请求体必须含 `llm_config` (env var name，禁止明文 key) |
| Java → Python 异步任务 | RabbitMQ `review.exchange` | routing key: `review.agent.task` / `review.pipeline.task` |
| Python → Java Tool 调用 | HTTP POST `/api/v1/tools/*` | 必须 session-based，Session TTL ≤ 10min |
| Python → LLM | HTTPS (OpenAI / Anthropic) | 超时 ≤ 120s，重试 ≤ 3 次 |

**禁止链路：**
- 禁止 Python Agent 直接访问 Git 仓库文件系统
- 禁止 Java Gateway 在 Simple 模式外硬编码 LLM Prompt
- 禁止跨 Session 复用 Tool Session ID

---

## 2. Agent 架构规范

### 2.1 三种审查引擎

| 引擎类型 | 实现位置 | 适用场景 |
|---------|---------|---------|
| `SIMPLE` | Java `LlmClient` | 单文件、低风险变更 |
| `PIPELINE` | Python `PipelineOrchestrator` | 标准审查，3 阶段流水线 |
| `MULTI_AGENT` | Python `MultiAgentOrchestrator` | 多文件、高风险变更，并行 Agent |

**引擎选择优先级：** `MULTI_AGENT` > `PIPELINE` > `SIMPLE`。CLI flag 覆盖 config。

### 2.2 Pipeline 模式架构

```
Input → SummaryStage → ReviewerStage → AggregationStage → Output
         (结构化摘要)   (多维审查)      (聚合去重)
```

**约束：**
- Pipeline Stage 必须实现 `PipelineStage` 协议（`async execute(context) -> context`）
- Stage 之间通过 `PipelineContext` 传递数据，禁止直接耦合
- 任意 Stage 异常必须被捕获并降级为 FAILED 状态，禁止中断整条 Pipeline

### 2.3 Multi-Agent 模式架构

```
DiffEntries → StrategyPlanner → ReviewStrategy
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
              SecurityAgent  PerformanceAgent  ArchitectureAgent
                    │               │               │
                    └───────┬───────┴───────┬───────┘
                            ▼               ▼
                     AgentMemory      ResultAggregation
                     (cross-agent)    (去重 + 加权)
                            │
                            ▼
                      ReviewResponse
```

**约束：**
- Agent 必须通过 `AgentRegistry` 注册，使用 `@AgentRegistry.register("name")` 装饰器
- Agent 并行执行必须使用 `asyncio.gather(return_exceptions=True)`，单 Agent 失败不影响其他
- Agent 间知识共享必须通过 `AgentMemory`，禁止直接引用其他 Agent 实例
- StrategyPlanner 必须基于文件类别 + 风险指标动态调整 Agent 权重

### 2.4 Agent 基类规范

所有 Agent 必须继承 `ReviewAgent` 基类并实现以下接口：

```python
class ReviewAgent(ABC):
    @property
    @abstractmethod
    def name(self) -> str: ...

    @property
    @abstractmethod
    def description(self) -> str: ...

    @property
    def default_weight(self) -> float:
        return 1.0

    @abstractmethod
    async def review(
        self, llm, diff_text, tool_client,
        focus_areas=None, additional_rules=None,
        max_iterations=8
    ) -> AgentReviewResult: ...
```

**禁止：**
- 禁止 Agent 直接依赖其他 Agent 的实现类
- 禁止 Agent 内部维护全局状态（仅允许 per-session 的局部状态）
- 禁止 Agent 绕过 `AgentMemory` 进行 Agent 间通信

---

## 3. Tool 调用规范

### 3.1 Tool 架构

```
Python Agent (LangChain @tool)
       │
       ▼
JavaToolClient (httpx.AsyncClient)
       │  HTTP POST /api/v1/tools/{tool_name}
       ▼
Java Tool Server (Javalin port 9090)
       │
       ▼
ToolSessionManager → FileAccessSandbox → 执行
```

### 3.2 已注册 Tool 清单

| Tool 名称 | 功能 | 副作用 |
|-----------|------|--------|
| `get_file_content` | 读取源文件内容 | 无（只读） |
| `get_diff_context` | 获取 diff 摘要/单文件 diff | 无（只读） |
| `get_method_definition` | 提取类结构、方法签名、调用边 | 无（只读） |
| `get_call_graph` | 查询调用者/被调用者/影响集 | 无（只读） |
| `get_related_files` | 查找依赖/继承关系 | 无（只读） |
| `semantic_search` | Top-5 语义代码搜索 | 无（只读） |

### 3.3 Tool 约束

**必须：**
- 所有 Tool 必须是 **只读** 操作（zero side-effect）
- Tool 输入必须通过 Pydantic Schema 校验
- Tool 输出必须是 `str` 类型（LangChain Agent 兼容）
- Tool 异常必须被捕获并返回描述性错误字符串，禁止抛出未处理异常
- Tool 调用必须携带 `X-Session-Id` Header

**禁止：**
- 禁止 Tool 内嵌业务逻辑（Tool 仅做数据获取，不做判断）
- 禁止 Tool 直接修改文件系统或发起写操作
- 禁止 Tool 绕过 `FileAccessSandbox` 访问文件
- 禁止在 Tool 实现中硬编码 URL 或认证信息

### 3.4 Session 生命周期

```
create_tool_session(base_url, diff_entries, project_dir, allowed_files)
       │
       │  返回 JavaToolClient(session_id=UUID)
       │
       ▼
   [Tool 调用 1..N]
       │
       ▼
destroy_tool_session(client)   ← 必须在 finally 块中调用
```

**约束：**
- Session TTL = 10 分钟，超时自动失效
- 每个 Review 请求创建独立 Session，禁止跨请求复用
- `destroy_tool_session` 必须在 `finally` 块中执行

---

## 4. Prompt Engineering 规范

### 4.1 Prompt 存储结构

```
services/agent/diffguard/prompts/
├── reviewagents/
│   ├── security-system.txt      # Security Agent System Prompt
│   ├── performance-system.txt   # Performance Agent System Prompt
│   └── architecture-system.txt  # Architecture Agent System Prompt
├── pipeline/
│   ├── summary-system.txt       # Summary Stage Prompt
│   ├── reviewer-system.txt      # Reviewer Stage Prompt
│   └── aggregation-system.txt   # Aggregation Stage Prompt
└── react-user.txt               # ReAct 通用 User Prompt
```

### 4.2 Prompt 模板规范

**必须：**
- System Prompt 和 User Prompt 必须分离存储
- Prompt 模板中的变量占位符使用 `{variable_name}` 格式
- Prompt 加载必须通过 `load_prompt(name)` 函数，禁止硬编码 Prompt 文本

**System Prompt 必须包含：**
1. 角色定义（你是什么专家）
2. 审查维度定义（关注什么）
3. 输出格式约束（JSON Schema）
4. 禁止行为声明（不能做什么）

**User Prompt 必须包含：**
1. `{diff_text}` 占位符（代码变更内容）
2. `{focus_areas}` 占位符（可选，由 StrategyPlanner 注入）
3. `{agent_scratchpad}` 占位符（ReAct Agent 必须）

### 4.3 输出结构约束

Agent 输出必须是合法 JSON，符合以下 Schema：

```json
{
  "has_critical": false,
  "summary": "string",
  "issues": [
    {
      "file": "string",
      "line": 0,
      "severity": "CRITICAL|WARNING|INFO",
      "type": "string",
      "message": "string",
      "suggestion": "string"
    }
  ],
  "highlights": ["string"]
}
```

**约束：**
- JSON 解析失败时必须降级为 minimal result（包含 summary + raw text 截断），禁止抛出异常中断流程
- `severity` 枚举值必须是 `CRITICAL | WARNING | INFO` 之一

### 4.4 Token 控制策略

| 层级 | 策略 |
|------|------|
| 单文件 diff | `max_tokens_per_file` 配置项截断 |
| Agent 输入 | jtokkit 估算 token 数，超限则截断低优先级文件 |
| Agent 输出 | `max_tokens` 由 LLM config 控制，默认由具体模型决定 |
| Pipeline 总量 | Summary 阶段压缩上下文，降低后续阶段 token 消耗 |

---

## 5. LLM 调用规范

### 5.1 Provider 抽象

```
LlmProvider (interface)
├── ClaudeHttpProvider   # Anthropic Claude API
└── OpenAiHttpProvider   # OpenAI GPT API
```

**约束：**
- Provider 选择由 `llm_config.provider` 决定（`"claude"` / `"openai"`）
- Python 侧通过 LangChain `ChatAnthropic` / `ChatOpenAI` 统一抽象
- API Key 必须通过环境变量注入，禁止出现在配置文件或代码中

### 5.2 重试策略

| 错误类型 | 最大重试 | 退避策略 |
|---------|---------|---------|
| Rate Limit (429) | 3 次 | 固定 15s |
| Server Error (5xx) | 2 次 | 指数退避 (base 5s) |
| 响应格式错误 (非 JSON) | 1 次 | Format Retry（二次调用请求格式化） |
| Timeout | 不重试 | 直接失败 |

### 5.3 超时控制

| 调用场景 | 超时上限 |
|---------|---------|
| Java → Python 审查请求 | 300s |
| Java → LLM 直接调用 | 120s (可配置) |
| Python → LLM 调用 | 120s (可配置) |
| Python → Java Tool 调用 | 30s |
| Git fetch 操作 | 30s |

### 5.4 幂等性设计

- Review 请求必须携带 `request_id`
- 数据库层面 `review_task` 表以 `request_id` 做幂等校验
- RabbitMQ 消费端必须处理消息重复投递（at-least-once 语义）
- Tool Session 基于 UUID，天然幂等

### 5.5 降级策略

```
优先级链：
  MULTI_AGENT → PIPELINE → SIMPLE → 静态规则结果
  RabbitMQ 异步 → HTTP 同步 → 本地降级
  LLM 调用失败 → 返回静态规则扫描结果 + 错误提示
```

**约束：** 降级路径上的每个环节必须有日志记录，禁止静默降级。

---

## 6. Agent 设计原则

### 6.1 可解释性

- 每个 Agent 必须在输出中包含 `summary` 字段说明审查结论
- ReAct Agent 的 `agent_scratchpad` 必须可追踪（记录推理步骤）
- StrategyPlanner 的策略决策（权重调整、Agent 启用）必须可审计

### 6.2 可观测性

**必须记录的日志：**

| 日志事件 | 级别 | 内容 |
|---------|------|------|
| Review 请求开始/结束 | INFO | request_id, mode, file_count |
| LLM 调用 | DEBUG | provider, model, token_usage |
| Tool 调用 | DEBUG | tool_name, latency_ms |
| Agent 完成 | INFO | agent_name, issue_count, has_critical |
| 异常 | ERROR | request_id, error, stacktrace |
| 降级 | WARN | 从什么降级到什么 |
| Circuit Breaker 状态变更 | WARN | provider, state (OPEN/HALF_OPEN/CLOSED) |

**Metrics 指标（Prometheus）：**
- `diffguard_review_total{status=success|failed}`
- `diffguard_review_duration_seconds`
- `diffguard_issues_total{severity,agent}`
- `diffguard_llm_tokens_total{provider}`

### 6.3 可扩展性

**新增 Agent 审查维度：**
1. 在 `diffguard/agent/builtin_agents/` 新建 Agent 类
2. 继承 `ReviewAgent`，实现 `name` / `description` / `review()`
3. 添加 `@AgentRegistry.register("dimension_name")` 装饰器
4. 在 `strategy/config.yaml` 添加默认权重和 focus areas
5. 在 `diffguard/prompts/reviewagents/` 添加 System Prompt

**新增 Pipeline Stage：**
1. 实现 `PipelineStage` 协议
2. 在 `build_default_pipeline()` 中注册，或在请求中自定义 stage 列表

**新增 Tool：**
1. Java 侧：在 Tool Server 添加新端点
2. Python 侧：在 `diffguard/tools/definitions.py` 添加 `make_*_tool(client)` 工厂函数
3. Agent 内通过 LangChain Tool 自动发现

### 6.4 安全约束

- `FileAccessSandbox` 必须：
  - 规范化路径（阻止 `../` 穿越）
  - 校验路径在 `projectRoot` 内
  - 校验文件在 `allowedFiles` 白名单中
- Webhook 必须：
  - HMAC-SHA256 签名校验
  - IP 速率限制
- API Key 必须：
  - 仅通过环境变量注入
  - 禁止日志打印
  - 禁止写入配置文件

---

## 7. 数据流约束

### 7.1 请求体结构（Java → Python）

```json
{
  "request_id": "uuid",
  "mode": "PIPELINE | MULTI_AGENT",
  "project_dir": "string",
  "diff_entries": [{ "file_path": "str", "content": "str", "change_type": "str" }],
  "llm_config": {
    "provider": "claude | openai",
    "model": "string",
    "api_key_env": "ENV_VAR_NAME",
    "base_url": "string",
    "max_tokens": 4096,
    "temperature": 0.1
  },
  "review_config": {},
  "tool_server_url": "http://host:9090",
  "allowed_files": ["relative/path/to/file.java"]
}
```

### 7.2 响应体结构（Python → Java）

```json
{
  "status": "COMPLETED | FAILED",
  "request_id": "uuid",
  "result": {
    "issues": [{ "file": "str", "line": 0, "severity": "str", "type": "str",
                 "message": "str", "suggestion": "str", "agent": "str" }],
    "summary": "string",
    "has_critical": false,
    "metadata": { "agents_used": ["str"], "strategy": "str" }
  }
}
```

### 7.3 跨服务通信约束

- Java → Python 请求中 `llm_config.api_key_env` 必须是环境变量名，Python 侧自行解析
- Tool 调用返回的文件内容禁止超出 `FileAccessSandbox` 白名单
- RabbitMQ 消息必须包含 `request_id` 和 `reply_to` queue，用于结果回传
- Dead Letter Exchange `review.dlx` 处理失败消息，必须有告警

---

## 8. 配置管理规范

### 8.1 配置层级

```
内置默认值 → 项目 application.yml → 用户目录 ~/.diffguard/config.yml → 环境变量覆盖
```

**优先级从低到高，高优先级覆盖低优先级同名配置项。**

### 8.2 敏感信息管理

| 配置项 | 存储方式 |
|-------|---------|
| LLM API Key | 环境变量（`ANTHROPIC_API_KEY` / `OPENAI_API_KEY`） |
| GitHub Token | 环境变量（`DIFFGUARD_GITHUB_TOKEN`） |
| MySQL 密码 | 环境变量（`DIFFGUARD_DB_PASSWORD`） |
| RabbitMQ 密码 | 环境变量（`DIFFGUARD_MQ_PASSWORD`） |
| Webhook Secret | 环境变量（`DIFFGUARD_WEBHOOK_SECRET`） |

**禁止：** 在任何配置文件、代码、日志中出现明文密钥。

---

## 9. 部署架构

### 9.1 Docker Compose 服务拓扑

```
diffguard-net (bridge network)
├── rabbitmq:5672        # 消息队列
├── mysql:3306           # 持久化
├── redis:6379           # 缓存 / 向量存储
├── diffguard-gateway    # Java Gateway
│   ├── :8080 (Webhook)
│   ├── :9090 (Tool Server)
│   └── :9091 (Prometheus Metrics)
└── diffguard-agent      # Python Agent
    └── :8000 (FastAPI)
```

### 9.2 健康检查

所有服务必须配置 health check。健康检查失败的服务必须被标记为 unhealthy 并触发告警。

### 9.3 资源限制

| 服务 | CPU | Memory | 超时 |
|------|-----|--------|------|
| Gateway | 2 core | 1GB | 300s/review |
| Agent | 2 core | 2GB | 300s/review |
| LLM 外部调用 | - | - | 120s/call |

---

## 10. 违规判定标准

以下行为视为违反本规范：

| 编号 | 违规行为 | 严重级别 |
|------|---------|---------|
| V-01 | Agent 未通过 AgentRegistry 注册 | CRITICAL |
| V-02 | Tool 包含写操作或副作用 | CRITICAL |
| V-03 | 明文存储 API Key | CRITICAL |
| V-04 | 绕过 FileAccessSandbox | CRITICAL |
| V-05 | 跨 Session 复用 Tool Session | HIGH |
| V-06 | Agent 输出不遵循 JSON Schema | HIGH |
| V-07 | 静默降级无日志记录 | HIGH |
| V-08 | Prompt 硬编码在代码中 | MEDIUM |
| V-09 | 缺少 request_id 追踪 | MEDIUM |
| V-10 | Tool 异常未捕获直接抛出 | MEDIUM |
