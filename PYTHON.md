# DiffGuard Python Agent 工程规范

> 本文档为 DiffGuard Python Agent 服务的工程约束规范。所有 Python 代码提交必须遵守。
> Code Review 中发现违反本文档的代码必须打回。

---

## 1. 技术栈约束

### 1.1 锁定版本

| 组件 | 版本 | 用途 |
|------|------|------|
| Python | ≥ 3.11 | 运行时 |
| FastAPI | ≥ 0.115.0 | HTTP 框架 |
| Uvicorn | ≥ 0.34.0 | ASGI Server |
| LangChain | ≥ 0.3.0 | Agent 框架 |
| langchain-openai | ≥ 0.3.0 | OpenAI Provider |
| langchain-anthropic | ≥ 0.3.0 | Anthropic Provider |
| httpx | ≥ 0.28.0 | 异步 HTTP 客户端 |
| Pydantic | ≥ 2.10.0 | 数据校验 / Schema |
| aio-pika | ≥ 9.4.0 | 异步 RabbitMQ |
| redis | ≥ 5.0.0 | Redis 客户端 |
| pytest | - | 测试框架 |
| pytest-asyncio | - | 异步测试 |

### 1.2 禁止引入

- 禁止引入 `requests` 库（必须使用 `httpx` 异步客户端）
- 禁止引入 `Flask` / `Django`（使用 FastAPI）
- 禁止引入 `tensorflow` / `torch`（本服务不做模型训练）
- 禁止引入 `langchain` < 0.3.0 的旧 API（`llm` / `chat_model` 旧接口）

---

## 2. 项目结构约束

### 2.1 包结构

```
diffguard/
├── main.py                         # FastAPI 应用入口 + Uvicorn 启动
├── api/
│   └── routes.py                   # HTTP 路由定义
├── agent/
│   ├── registry.py                 # AgentRegistry (装饰器注册)
│   ├── base.py                     # ReviewAgent 抽象基类
│   ├── memory.py                   # AgentMemory (跨 Agent 共享)
│   ├── strategy_planner.py         # StrategyPlanner (策略规划)
│   ├── pipeline_orchestrator.py    # Pipeline 编排器
│   ├── multi_agent_orchestrator.py # Multi-Agent 编排器
│   ├── builtin_agents/
│   │   ├── security.py             # 安全审查 Agent
│   │   ├── performance.py          # 性能审查 Agent
│   │   └── architecture.py         # 架构审查 Agent
│   ├── strategy/
│   │   └── config.yaml             # 策略配置
│   └── pipeline/
│       ├── stages.py               # Pipeline Stage 实现
│       └── context.py              # PipelineContext
├── tools/
│   ├── tool_client.py              # JavaToolClient (HTTP)
│   └── definitions.py              # LangChain @tool 工厂
├── models/
│   ├── request.py                  # ReviewRequest (Pydantic)
│   └── response.py                 # ReviewResponse / AgentReviewResult
├── prompts/                        # Prompt 模板文件
│   ├── reviewagents/
│   │   ├── security-system.txt
│   │   ├── performance-system.txt
│   │   └── architecture-system.txt
│   ├── pipeline/
│   │   ├── summary-system.txt
│   │   ├── reviewer-system.txt
│   │   └── aggregation-system.txt
│   └── react-user.txt
├── mq/
│   └── consumer.py                 # RabbitMQ 消费者
└── config.py                       # 配置加载
```

### 2.2 依赖方向

```
api → agent → tools → (httpx → Java Gateway)
api → agent → models
agent → prompts (只读文件)
mq → agent
```

**禁止：**
- 禁止 `tools/` 依赖 `agent/`（Tool 不感知调用者）
- 禁止 `models/` 依赖 `agent/` 或 `tools/`
- 禁止 `prompts/` 目录包含 Python 代码（纯文本模板）

---

## 3. Agent 架构规范

### 3.1 Agent 注册机制

```python
# 正确：通过装饰器注册
@AgentRegistry.register("security")
class SecurityAgent(ReviewAgent):
    ...

# 禁止：手动维护 Agent 列表
AGENTS = {"security": SecurityAgent()}  # ❌
```

**约束：**
- 所有 Agent 必须使用 `@AgentRegistry.register("name")` 注册
- 注册名必须与 Agent 的 `name` property 一致
- 重复注册必须打印 WARN 日志并覆盖
- `AgentRegistry.create("name")` 是实例化 Agent 的唯一方式

### 3.2 Agent 基类协议

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
        self,
        llm: BaseChatModel,
        diff_text: str,
        tool_client: JavaToolClient,
        focus_areas: list[str] | None = None,
        additional_rules: list[str] | None = None,
        max_iterations: int = 8,
    ) -> AgentReviewResult: ...
```

**约束：**
- `review()` 必须是 `async` 方法
- `max_iterations` 默认 8，上限 15，禁止无限制循环
- 返回值必须是 `AgentReviewResult`（Pydantic model），禁止返回裸 dict
- Agent 内部禁止捕获并吞没所有异常（必须向上传播或降级为 minimal result）

### 3.3 ReAct Agent 构建模式

每个 builtin Agent 必须遵循以下构建流程：

```python
async def review(self, llm, diff_text, tool_client, **kwargs):
    # 1. 加载 System Prompt
    system_prompt = _load_prompt("reviewagents/security-system.txt")

    # 2. 注入 focus_areas 和 additional_rules
    if kwargs.get("focus_areas"):
        system_prompt += "\n\n## Focus Areas\n" + "\n".join(f"- {a}" for a in kwargs["focus_areas"])

    # 3. 构造 Tools
    tools = [
        make_file_content_tool(tool_client),
        make_diff_context_tool(tool_client),
        make_method_definition_tool(tool_client),
        make_call_graph_tool(tool_client),
        make_related_files_tool(tool_client),
        make_semantic_search_tool(tool_client),
    ]

    # 4. 构建 Prompt Template
    prompt = ChatPromptTemplate.from_messages([
        ("system", system_prompt),
        ("human", user_prompt),
        ("placeholder", "{agent_scratchpad}"),
    ])

    # 5. 创建 Agent + Executor
    agent = create_tool_calling_agent(llm, tools, prompt)
    executor = AgentExecutor(agent=agent, tools=tools, max_iterations=max_iterations)

    # 6. 执行
    result = await executor.ainvoke({"diff_text": diff_text, ...})

    # 7. 解析输出
    return _parse_agent_output(result["output"])
```

**禁止：**
- 禁止跳过 Tool 直接让 LLM 猜测代码内容
- 禁止 `AgentExecutor` 不设 `max_iterations`
- 禁止在 Agent 内部直接访问文件系统或 Git

---

## 4. Pipeline 架构规范

### 4.1 Stage 协议

```python
class PipelineStage(Protocol):
    async def execute(self, context: PipelineContext) -> PipelineContext: ...
```

### 4.2 默认 Pipeline

```
SummaryStage → ReviewerStage → AggregationStage
  (LLM摘要)    (多维审查)      (聚合去重)
```

**约束：**
- Stage 必须按顺序执行，每个 Stage 接收上一个 Stage 的 `PipelineContext` 产出
- 任意 Stage 失败时必须将 `PipelineContext.status` 设为 `FAILED` 并跳过后续 Stage
- Stage 内部禁止直接修改 `PipelineContext` 的不可变字段

### 4.3 PipelineContext

```python
@dataclass
class PipelineContext:
    diff_text: str                          # 原始 diff（不可变）
    diff_summary: str | None = None         # SummaryStage 产出
    review_issues: list[ReviewIssue] = field(default_factory=list)  # ReviewerStage 产出
    aggregated_result: ReviewResponse | None = None  # AggregationStage 产出
    status: str = "RUNNING"                 # RUNNING / COMPLETED / FAILED
    metadata: dict = field(default_factory=dict)
```

---

## 5. Multi-Agent 编排规范

### 5.1 编排流程

```python
class MultiAgentOrchestrator:
    async def run(self) -> ReviewResponse:
        llm = _create_llm(self.request.llm_config)
        tool_client = await create_tool_session(...)
        strategy = StrategyPlanner().plan(diff_entries)
        memory = AgentMemory()

        # 并行执行所有 Agent
        agents = [AgentRegistry.create(name) for name in strategy.get_enabled_agent_names()]
        tasks = [self._run_agent(agent, llm, diff, tool_client, strategy, memory) for agent in agents]
        results = await asyncio.gather(*tasks, return_exceptions=True)

        # 聚合
        return self._aggregate(results)
```

### 5.2 并发约束

**必须：**
- 使用 `asyncio.gather(return_exceptions=True)` 并行执行 Agent
- 单 Agent 异常必须被捕获为 `Exception` 对象，不影响其他 Agent
- 至少 1 个 Agent 成功时视为 review 成功
- 所有 Agent 均失败时返回 `ReviewResponse(status="FAILED")`

**禁止：**
- 禁止使用 `threading` / `multiprocessing`（本服务是 async 架构）
- 禁止 `asyncio.gather(return_exceptions=False)`（单 Agent 崩溃会中断全部）

### 5.3 AgentMemory 约束

```python
@dataclass
class AgentMemory:
    findings: list[str]           # 跨 Agent 发现
    completed_agents: list[str]   # 已完成的 Agent 列表
    shared_context: dict[str, Any] # Agent 结果摘要
```

**约束：**
- `add_finding(agent_name, finding)` 必须标注来源 Agent
- `get_findings_for(agent_name)` 必须排除该 Agent 自身的发现（防止自我引用）
- AgentMemory 生命周期 = 单次 Review 请求，禁止跨请求持久化
- AgentMemory 不是线程安全的，仅在单 asyncio event loop 中使用

### 5.4 StrategyPlanner 约束

```python
class StrategyPlanner:
    def plan(self, entries: list[DiffFileEntry]) -> ReviewStrategy:
        profile = profile(entries)               # 分析 diff 特征
        category = _primary_category(profile)    # 主要文件类别
        weights = _apply_category_weights(...)    # 按类别调整权重
        _adjust_for_risk(profile, weights)        # 按风险调整权重
        return ReviewStrategy(...)
```

**约束：**
- 权重 ≤ 0 的 Agent 不启用（`create_all_enabled(weights)` 过滤）
- 风险等级映射：score 0-1 → LOW, 2-3 → MEDIUM, 4-5 → HIGH, 6-7 → CRITICAL
- YAML 配置缺失时必须使用硬编码默认值（等权重 1.0），禁止抛出异常
- 新增 Agent 必须在 `strategy/config.yaml` 添加默认权重

---

## 6. Tool 开发规范

### 6.1 Tool 定义模式

```python
# Tool 工厂函数模式（禁止直接定义 @tool）
def make_file_content_tool(client: JavaToolClient):
    @tool
    async def get_file_content(file_path: str) -> str:
        """Read the full content of a source file."""
        try:
            result = await client.get_file_content(file_path)
            return result
        except Exception as e:
            return f"Error reading file {file_path}: {e}"
    return get_file_content
```

### 6.2 Tool 约束

**必须：**
- 所有 Tool 必须是工厂函数（closure over `JavaToolClient`）
- Tool 函数必须是 `async`
- Tool 函数签名参数必须有类型注解（LangChain 需要）
- Tool 必须有 docstring（LangChain 用作 Tool 描述）
- Tool 返回值必须是 `str`
- Tool 异常必须捕获并返回描述性错误字符串

**禁止：**
- 禁止 Tool 内嵌业务判断逻辑（if-else 审查规则等）
- 禁止 Tool 直接发起写操作
- 禁止 Tool 缓存结果（缓存由 Java Tool Server 侧管理）
- 禁止 Tool 使用全局 `httpx.Client`（每个 session 独立 client）

### 6.3 Tool Client 生命周期

```python
# 必须在 finally 中清理
tool_client = await create_tool_session(base_url, diff_entries, project_dir, allowed_files)
try:
    # ... Agent 使用 tool_client ...
    pass
finally:
    await destroy_tool_session(tool_client)
```

**约束：**
- `create_tool_session` 生成 UUID v4 session_id
- `destroy_tool_session` 必须调用 Java 侧 DELETE + 关闭 httpx client
- Session TTL = 10 分钟（Java 侧强制）
- 禁止跨 Review 请求复用 Session

---

## 7. Prompt Engineering 规范

### 7.1 Prompt 加载

```python
def _load_prompt(name: str) -> str:
    """从 prompts/ 目录加载模板文件"""
    prompt_dir = Path(__file__).parent.parent / "prompts"
    return (prompt_dir / name).read_text(encoding="utf-8")
```

**约束：**
- Prompt 必须作为独立 `.txt` 文件存储在 `prompts/` 目录下
- 禁止在 Python 代码中硬编码 Prompt 文本（> 3 行的字符串）
- Prompt 加载失败时必须抛出明确异常（`FileNotFoundError`），禁止使用空字符串降级

### 7.2 System Prompt 结构模板

```
# 角色定义
You are a senior {dimension} reviewer...

# 审查维度
Focus on:
- {dimension_specific_items}

# 输出格式
Respond with valid JSON:
{json_schema}

# 禁止行为
- Do NOT suggest changes outside the diff scope
- Do NOT output anything outside the JSON structure
```

### 7.3 ReAct User Prompt 模板

```
Review the following code changes:

{diff_text}

Use the available tools to investigate the codebase as needed.
Provide your analysis as structured JSON.
```

**约束：**
- `{diff_text}` 占位符必须存在
- `{agent_scratchpad}` 占位符在 ReAct Agent 中必须存在
- 变量注入使用 LangChain `ChatPromptTemplate`，禁止手动 `str.format()`

### 7.4 输出解析

```python
def _parse_agent_output(output_text: str) -> AgentReviewResult:
    try:
        data = json.loads(output_text)
        return AgentOutput(**data).to_result()
    except (json.JSONDecodeError, ValidationError):
        return AgentReviewResult(
            summary=output_text[:500],
            issues=[],
            has_critical=False,
        )
```

**约束：**
- JSON 解析失败时降级为 minimal result，禁止抛出异常
- Pydantic 校验失败时同样降级为 minimal result
- 降级时 `summary` 截断至 500 字符
- 降级事件必须记录 WARN 日志

---

## 8. LLM 调用规范

### 8.1 Provider 创建

```python
def _create_llm(config: LlmConfig) -> BaseChatModel:
    if config.provider == "claude":
        return ChatAnthropic(
            model=config.model,
            max_tokens=config.max_tokens or 4096,
            temperature=config.temperature or 0.1,
            api_key=os.environ.get(config.api_key_env),
            timeout=config.timeout or 120,
        )
    elif config.provider == "openai":
        return ChatOpenAI(
            model=config.model,
            max_tokens=config.max_tokens or 4096,
            temperature=config.temperature or 0.1,
            api_key=os.environ.get(config.api_key_env),
            base_url=config.base_url,
            timeout=config.timeout or 120,
        )
```

**约束：**
- API Key 必须通过 `os.environ.get(config.api_key_env)` 读取
- `temperature` 审查场景建议 0.1（确定性优先）
- `max_tokens` 默认 4096
- `timeout` 默认 120s

### 8.2 结构化输出

```python
# SummaryStage 使用 with_structured_output
summary_llm = llm.with_structured_output(_DiffSummary)
result = await summary_llm.ainvoke(...)
```

**约束：**
- 需要 Pydantic Schema 约束输出的场景必须使用 `with_structured_output`
- 不支持 `with_structured_output` 的场景使用 JSON 解析 + 降级策略

### 8.3 Token 控制

| 场景 | 策略 |
|------|------|
| diff 输入 | Java 侧按 `max_tokens_per_file` 截断后传入 |
| Agent 输出 | `max_tokens` 参数控制 |
| Pipeline 间传递 | Summary 阶段压缩，降低后续阶段输入量 |

### 8.4 流式支持（Streaming）

- 当前版本不要求 streaming 输出
- 如需实现，必须使用 `astream` 方法 + SSE (Server-Sent Events)
- Streaming 场景下 Tool 调用必须完整完成后才能流式输出结果

---

## 9. 代码分析能力

### 9.1 Tool 能力矩阵

| 分析维度 | Tool | 数据源 |
|---------|------|--------|
| 文件内容 | `get_file_content` | Java Tool Server (FileAccessSandbox) |
| Diff 上下文 | `get_diff_context` | Java Tool Server |
| 类/方法结构 | `get_method_definition` | JavaParser AST |
| 调用关系 | `get_call_graph` | CodeGraph (BFS) |
| 依赖/继承 | `get_related_files` | CodeGraph |
| 语义搜索 | `semantic_search` | CodeRAG (TF-IDF / OpenAI Embedding) |

### 9.2 AST 分析（Java 侧）

Python Agent 不直接执行 AST 解析。所有 AST 能力通过 Java Tool Server 提供。

**约束：**
- 禁止在 Python 侧引入 `tree-sitter` / `ast` 等解析库做 AST 分析
- AST 相关查询必须通过 Tool 调用 Java 侧

### 9.3 静态规则

Python 侧可选实现 `StaticRulesStage`（在 Pipeline 中作为独立 Stage）。

**约束：**
- 静态规则必须在 LLM 调用前执行（零 token 消耗）
- 静态规则结果与 LLM 结果合并时，静态规则优先（高置信度）

---

## 10. Pydantic 模型规范

### 10.1 请求/响应模型

```python
class ReviewRequest(BaseModel):
    request_id: str
    mode: Literal["PIPELINE", "MULTI_AGENT"]
    project_dir: str
    diff_entries: list[DiffEntry]
    llm_config: LlmConfig
    review_config: dict | None = None
    tool_server_url: str
    allowed_files: list[str]

class ReviewResponse(BaseModel):
    status: Literal["COMPLETED", "FAILED"]
    request_id: str
    result: ReviewResult | None = None

class AgentReviewResult(BaseModel):
    summary: str
    issues: list[ReviewIssue]
    has_critical: bool
```

### 10.2 模型约束

**必须：**
- 所有跨服务传输的数据结构必须定义 Pydantic Model
- Model 字段必须有类型注解
- 必须使用 Pydantic v2 API（`model_validate` / `model_dump`）

**禁止：**
- 禁止使用 `dict` 作为函数参数或返回值（必须定义 Model）
- 禁止 Pydantic Model 包含业务逻辑（纯数据结构）
- 禁止使用 `model_config = ConfigDict(arbitrary_types_allowed=True)` 规避类型检查

---

## 11. 并发与性能规范

### 11.1 asyncio 约束

**必须：**
- 所有 I/O 操作（HTTP、DB、Redis、MQ）必须使用 async 版本
- `httpx.AsyncClient` 必须复用（每个 Tool Session 创建一个，结束时关闭）
- 并行 Agent 执行使用 `asyncio.gather`

**禁止：**
- 禁止在 async 函数中使用同步阻塞调用（`requests.get`、`time.sleep`）
- 禁止使用 `asyncio.run()` 嵌套（FastAPI 已管理 event loop）
- 禁止无限制并发（`asyncio.gather` 任务数 ≤ Agent 数量，通常 ≤ 5）

### 11.2 批量推理

```python
# Pipeline 模式：串行 Stage，无需批量
# Multi-Agent 模式：并行 Agent，每个 Agent 内串行 Tool 调用
```

**约束：**
- 单 Agent 内的 Tool 调用串行执行（ReAct 模式决定的）
- Agent 间并行执行（`asyncio.gather`）
- 禁止 Agent 内部并发调用 LLM（保持推理链线性）

---

## 12. 可观测性规范

### 12.1 日志规范

```python
import logging
logger = logging.getLogger("diffguard.agent")

# 正确：结构化日志 + request_id 关联
logger.info("Agent completed: agent=%s issues=%d has_critical=%s",
            agent.name, len(result.issues), result.has_critical)

# 禁止
logger.info(f"Agent {agent.name} done")  # ❌ f-string 格式化（性能差 + 无法延迟格式化）
logger.info("API key: %s", api_key)       # ❌ 日志中打印密钥
```

**约束：**
- 使用标准 `logging` 模块，logger 名称为 `diffguard.*`
- 日志级别：正常流程 INFO，调试详情 DEBUG，降级/异常 WARN/ERROR
- request_id 必须贯穿整个 Review 请求的日志链路
- 禁止使用 `print()` 替代日志

### 12.2 必须记录的事件

| 事件 | 级别 | 字段 |
|------|------|------|
| Review 请求接收 | INFO | request_id, mode, file_count |
| Agent 开始 | INFO | agent_name |
| Agent 完成 | INFO | agent_name, issue_count, has_critical, duration_ms |
| Tool 调用 | DEBUG | tool_name, latency_ms |
| LLM 调用 | DEBUG | provider, model, token_usage |
| JSON 解析失败降级 | WARN | agent_name, raw_text_length |
| Session 创建/销毁 | DEBUG | session_id |
| RabbitMQ 消息消费 | INFO | queue, routing_key |
| 异常 | ERROR | request_id, exception, traceback |

### 12.3 Token Usage 追踪

- 每次 LLM 调用必须记录 `token_usage`（prompt_tokens + completion_tokens）
- 汇总 token 用量写入 `ReviewResponse.metadata`
- 禁止忽略 token 统计（成本控制要求）

---

## 13. API 路由规范

### 13.1 端点定义

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/api/v1/review` | 提交审查请求 |
| GET | `/api/v1/health` | 健康检查 |

### 13.2 请求处理约束

```python
@router.post("/api/v1/review", response_model=ReviewResponse)
async def review(request: ReviewRequest):
    ...
```

**约束：**
- 请求体必须通过 Pydantic Model 校验（FastAPI 自动处理）
- 响应必须是 `ReviewResponse` 类型
- 超时控制由调用方（Java Gateway）管理，Python 侧不设全局请求超时
- 异常必须返回结构化错误响应，禁止返回裸 500

### 13.3 健康检查

```python
@router.get("/api/v1/health")
async def health():
    return {"status": "UP"}
```

---

## 14. 消息队列规范

### 14.1 RabbitMQ 消费

```python
# ReviewTaskConsumer
async def consume(self):
    async with aio_pika.connect_robust(amqp_url) as connection:
        channel = await connection.channel()
        queue = await channel.declare_queue("review.agent.queue", ...)
        async with queue.iterator() as iterator:
            async for message in iterator:
                await self._handle_message(message)
```

### 14.2 约束

**必须：**
- 使用 `aio_pika.connect_robust`（自动重连）
- 消费失败的消息必须 nack（触发 Dead Letter Exchange）
- 消息处理必须幂等（基于 `request_id`）
- RabbitMQ 不可用时服务必须仍能响应 HTTP 请求（降级为仅同步模式）

**禁止：**
- 禁止消息消费中抛出未处理异常（必须 catch + nack）
- 禁止消费端跳过消息确认（必须 ack/nack）

---

## 15. 测试规范

### 15.1 测试要求

| 层级 | 测试类型 | 要求 |
|------|---------|------|
| `models/` | Pydantic 校验测试 | 每个 Model 必须有 positive + negative case |
| `agent/registry.py` | 注册机制测试 | 验证注册、查找、创建 |
| `agent/builtin_agents/` | Agent 集成测试 | Mock LLM + Tool，验证输出结构 |
| `agent/strategy_planner.py` | 策略规划测试 | 覆盖各 FileCategory + RiskLevel 组合 |
| `tools/definitions.py` | Tool 单元测试 | Mock JavaToolClient，验证输入输出 |
| `api/routes.py` | API 集成测试 | FastAPI TestClient |

### 15.2 测试约束

```python
# 异步测试
@pytest.mark.asyncio
async def test_security_agent():
    mock_llm = ...
    mock_client = ...
    agent = SecurityAgent()
    result = await agent.review(mock_llm, "diff text", mock_client)
    assert isinstance(result, AgentReviewResult)
```

**必须：**
- 异步测试使用 `@pytest.mark.asyncio`（`asyncio_mode = "auto"` 已配置）
- Mock 外部依赖：LLM、Tool Client、RabbitMQ
- 每个 Agent 至少测试：正常输出、JSON 解析失败降级、Tool 调用失败

**禁止：**
- 禁止在测试中调用真实 LLM API
- 禁止在测试中启动真实 HTTP Server
- 禁止测试中硬编码 API Key

### 15.3 测试结构

```
tests/
├── test_models.py           # Pydantic Model 测试
├── test_registry.py         # AgentRegistry 测试
├── test_strategy_planner.py # StrategyPlanner 测试
├── test_memory.py           # AgentMemory 测试
├── test_tools.py            # Tool 定义测试
├── test_agents/
│   ├── test_security.py     # SecurityAgent 测试
│   ├── test_performance.py  # PerformanceAgent 测试
│   └── test_architecture.py # ArchitectureAgent 测试
├── test_pipeline.py         # Pipeline 编排测试
├── test_multi_agent.py      # Multi-Agent 编排测试
└── test_api.py              # API 路由测试
```

---

## 16. 配置与环境规范

### 16.1 环境变量

| 变量名 | 用途 | 必填 |
|-------|------|------|
| `ANTHROPIC_API_KEY` | Claude API Key | 使用 Claude 时 |
| `OPENAI_API_KEY` | OpenAI API Key | 使用 OpenAI 时 |
| `DIFFGUARD_AGENT_URL` | Agent 服务 URL | 否（默认 `http://localhost:8000`） |
| `DIFFGUARD_MQ_HOST` | RabbitMQ Host | 使用 MQ 时 |
| `DIFFGUARD_MQ_PASSWORD` | RabbitMQ 密码 | 使用 MQ 时 |
| `DIFFGUARD_REDIS_HOST` | Redis Host | 使用 Redis 向量存储时 |

### 16.2 Agent 配置文件

```yaml
# agent/strategy/config.yaml
agents:
  security:
    weight: 1.2
    focus_areas:
      - "SQL injection via string concatenation"
      - "XSS vulnerabilities"
      - "Authentication bypass"
  performance:
    weight: 1.0
    focus_areas:
      - "N+1 queries"
      - "Memory leaks"
      - "Inefficient algorithms"
  architecture:
    weight: 1.0
    focus_areas:
      - "Layer violations"
      - "Coupling issues"
      - "SOLID violations"
```

**约束：**
- 配置缺失时使用硬编码默认值，禁止启动失败
- 权重 ≤ 0 的 Agent 不参与审查
- 新增 Agent 必须在此文件添加配置条目

---

## 17. Docker 构建规范

```dockerfile
FROM python:3.12-slim
# 禁止使用 python:3.12（完整镜像，体积过大）
# 使用 uv 进行快速依赖安装
```

**约束：**
- 构建产物基于 `diffguard/` 目录（非 `app/`）
- 禁止在镜像中包含测试文件
- 禁止在镜像中硬编码配置（通过环境变量注入）
