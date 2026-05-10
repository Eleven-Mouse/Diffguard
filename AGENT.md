# DiffGuard Agent 架构规范

> 本文档为 DiffGuard AI Agent 系统的架构约束规范。所有涉及 Agent 模块的设计、开发、测试行为必须遵守本文档。
> 违反本文档的代码必须在 Code Review 阶段被拒绝。

---

## 1. 项目架构总览

### 1.1 系统定位

DiffGuard 是 AI 驱动的多维度代码审查系统。核心能力：通过 Pipeline 并行执行多维度审查（安全/逻辑/质量），聚合输出结构化审查结论。

### 1.2 双服务架构

```
┌─────────────────────────────────────────────────────────┐
│                    DiffGuard Gateway                     │
│                Java 21 / Javalin                         │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐              │
│  │ Webhook  │  │ Tool     │  │ Review   │              │
│  │ Adapter  │  │ Server   │  │ Engine   │              │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘              │
│       │              │              │                     │
│       └──────────────┴──────────────┘                     │
│                        │                                   │
│                        │  HTTP POST /api/v1/review         │
└───────────────────────┼──────────────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────────────┐
│                   DiffGuard Agent                         │
│              Python 3.11+ / FastAPI / LangChain            │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐ │
│  │              PipelineOrchestrator                    │ │
│  │  SummaryStage → ReviewerStage → AggregationStage   │ │
│  │                   → FalsePositiveFilterStage        │ │
│  └─────────────────────────────────────────────────────┘ │
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
| Python → Java Tool 调用 | HTTP POST `/api/v1/tools/*` | 必须 session-based，Session TTL ≤ 10min |
| Python → LLM | HTTPS (OpenAI / Anthropic) | 超时 ≤ 120s，重试 ≤ 3 次 |

**禁止链路：**
- 禁止 Python Agent 直接访问 Git 仓库文件系统
- 禁止跨 Session 复用 Tool Session ID

---

## 2. Pipeline 架构规范

### 2.1 Pipeline 模式架构

```
Input → SummaryStage → ReviewerStage → AggregationStage → FalsePositiveFilterStage → Output
         (结构化摘要)   (多维并行审查)   (聚合去重)        (误报过滤)
```

### 2.2 Stage 规范

每个 Stage 必须实现 `PipelineStage` 协议：

```python
class PipelineStage(ABC):
    @property
    @abstractmethod
    def name(self) -> str: ...

    @abstractmethod
    async def execute(self, context: PipelineContext) -> PipelineContext: ...
```

**约束：**
- Stage 之间通过 `PipelineContext` 传递数据，禁止直接耦合
- 任意 Stage 异常必须被捕获并降级为 FAILED 状态，禁止中断整条 Pipeline
- Pipeline 执行顺序：Summary → Reviewer → Aggregation → Filter

### 2.3 ReviewerStage 并行执行

ReviewerStage 内部并行执行多个 Reviewer：

```python
# 当前支持的 Reviewers
reviewers = [
    ("security", "pipeline/security-system.txt", "pipeline/security-user.txt"),
    ("logic", "pipeline/logic-system.txt", "pipeline/logic-user.txt"),
    ("quality", "pipeline/quality-system.txt", "pipeline/quality-user.txt"),
]
```

当 `tool_client` 可用时，Reviewer 以 LangChain ReAct Agent 模式运行，可调用 Tool Server 获取代码上下文。当 `tool_client` 不可用时，降级为直接 LLM 结构化输出。

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
ToolSessionManager → 执行
```

### 3.2 已注册 Tool 清单

| Tool 名称 | 功能 |
|-----------|------|
| `get_file_content` | 读取源文件内容 |
| `get_diff_context` | 获取 diff 摘要/单文件 diff |
| `get_method_definition` | 提取类结构、方法签名、调用边 |
| `get_call_graph` | 查询调用者/被调用者/影响集 |
| `get_related_files` | 查找依赖/继承关系 |
| `semantic_search` | Top-5 语义代码搜索 |

### 3.3 Tool 约束

**必须：**
- 所有 Tool 必须是 **只读** 操作
- Tool 输入必须通过 Pydantic Schema 校验
- Tool 输出必须是 `str` 类型
- Tool 异常必须被捕获并返回描述性错误字符串
- Tool 调用必须携带 `X-Session-Id` Header

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

---

## 4. Prompt Engineering 规范

### 4.1 Prompt 存储结构

```
services/agent/app/llm/prompts/
├── pipeline/
│   ├── diff-summary-system.txt    # Summary Stage System Prompt
│   ├── diff-summary-user.txt      # Summary Stage User Prompt
│   ├── security-system.txt        # Security Reviewer System Prompt
│   ├── security-user.txt          # Security Reviewer User Prompt
│   ├── logic-system.txt           # Logic Reviewer System Prompt
│   ├── logic-user.txt             # Logic Reviewer User Prompt
│   ├── quality-system.txt         # Quality Reviewer System Prompt
│   ├── quality-user.txt           # Quality Reviewer User Prompt
│   ├── aggregation-system.txt     # Aggregation Stage System Prompt
│   └── aggregation-user.txt       # Aggregation Stage User Prompt
├── reviewagents/                   # 预留扩展目录
│   ├── security-system.txt
│   ├── performance-system.txt
│   └── architecture-system.txt
└── react-user.txt                  # ReAct Agent User Prompt
```

### 4.2 Prompt 模板规范

**必须：**
- System Prompt 和 User Prompt 必须分离存储
- Prompt 模板中的变量占位符使用 `{{variable_name}}` 格式
- Prompt 加载必须通过 `load_prompt(name)` 函数

**System Prompt 必须包含：**
1. 角色定义
2. 审查维度定义
3. 输出格式约束（JSON Schema）
4. 禁止行为声明

**User Prompt 必须包含：**
1. `{{diff}}` 占位符（代码变更内容）
2. `{{summary}}` 占位符（SummaryStage 输出）

### 4.3 输出结构约束

Agent/Stage 输出必须是合法 JSON：

```json
{
  "summary": "string",
  "issues": [
    {
      "severity": "CRITICAL|WARNING|INFO",
      "file": "string",
      "line": 0,
      "type": "string",
      "message": "string",
      "suggestion": "string",
      "confidence": 0.0
    }
  ]
}
```

---

## 5. LLM 调用规范

### 5.1 Provider 抽象

```python
# 支持的 Provider
providers = ["claude", "openai"]

# 通过 LangChain 统一抽象
from langchain_anthropic import ChatAnthropic
from langchain_openai import ChatOpenAI
```

### 5.2 重试策略

| 错误类型 | 最大重试 | 策略 |
|---------|---------|------|
| Rate Limit (429) | 3 次 | 固定 15s 退避 |
| Server Error (5xx) | 2 次 | 指数退避 (base 5s) |
| Timeout | 不重试 | 直接失败 |

### 5.3 超时控制

| 调用场景 | 超时上限 |
|---------|---------|
| Java → Python 审查请求 | 300s |
| Python → LLM 调用 | 120s |
| Python → Java Tool 调用 | 10s |

---

## 6. False Positive 过滤

### 6.1 两阶段过滤架构

```
Issues → HardExclusionRules → [LLM Verification (可选)] → Filtered Issues
          (正则快速过滤)        (AI 二次验证)
```

### 6.2 Hard Exclusion Rules

当前排除的误报模式：
- DOS/资源耗尽类问题
- 通用性能建议（无具体实现）
- 测试文件中的发现
- 文档文件中的发现
- 非 C/C++ 代码的内存安全问题

### 6.3 Precedent 机制

```python
# 已知的误报模式（不报告）
precedents = [
    {"pattern": "JPA @Query with :named parameters", "verdict": "not_sql_injection"},
    {"pattern": "PreparedStatement with parameter binding", "verdict": "not_sql_injection"},
    {"pattern": "Spring @PreAuthorize", "verdict": "not_missing_auth"},
    # ...
]
```

---

## 7. 可扩展性规范

### 7.1 新增 Reviewer

1. 在 `llm/prompts/pipeline/` 添加 `xxx-system.txt` 和 `xxx-user.txt`
2. 在 `ReviewerStage.__init__` 中注册新的 reviewer tuple

```python
# 示例：新增 Performance Reviewer
class ReviewerStage(PipelineStage):
    def __init__(self, reviewers=None):
        self._reviewers = reviewers or [
            ("security", "pipeline/security-system.txt", "pipeline/security-user.txt"),
            ("logic", "pipeline/logic-system.txt", "pipeline/logic-user.txt"),
            ("quality", "pipeline/quality-system.txt", "pipeline/quality-user.txt"),
            # 新增
            ("performance", "pipeline/performance-system.txt", "pipeline/performance-user.txt"),
        ]
```

### 7.2 新增 Pipeline Stage

1. 实现 `PipelineStage` 协议
2. 在 `build_default_pipeline()` 中注册

```python
def build_default_pipeline():
    return [
        SummaryStage(),
        ReviewerStage(),
        AggregationStage(),
        FalsePositiveFilterStage(),
        # 新增 Stage
        # CustomStage(),
    ]
```

### 7.3 新增 Tool

1. Java 侧：在 Tool Server 添加新端点
2. Python 侧：在 `tools/definitions.py` 添加工厂函数

```python
def make_custom_tool(tool_client):
    @tool
    async def custom_tool(query: str) -> str:
        resp = await tool_client._post("/api/v1/tools/custom", {"query": query})
        return resp.result if resp.success else f"Error: {resp.error}"
    return custom_tool
```

---

## 8. 配置规范

### 8.1 LLM 配置

```python
class LlmConfig(BaseModel):
    provider: Literal["openai", "claude"] = "openai"
    model: str = "gpt-4o"
    api_key: str = ""  # 支持环境变量注入
    api_key_env: str = "DIFFGUARD_API_KEY"
    base_url: str | None = None
    max_tokens: int = 16384
    temperature: float = 0.3
    timeout_seconds: int = 300
```

### 8.2 敏感信息

API Key 必须通过环境变量注入，禁止出现在配置文件或代码中。

---

## 9. 部署架构

### 9.1 Docker Compose

```yaml
services:
  diffguard-gateway:
    ports:
      - "8080:8080"  # Webhook
      - "9090:9090"  # Tool Server
  diffguard-agent:
    ports:
      - "8000:8000"  # FastAPI
    environment:
      - JAVA_TOOL_SERVER_URL=http://diffguard-gateway:9090
```

### 9.2 健康检查

```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8000/api/v1/health"]
  interval: 10s
  timeout: 5s
  retries: 5
```

---

## 10. 未来演进路线图

### Phase 1: 完善当前 Pipeline
- [x] 4阶段 Pipeline 实现
- [ ] 添加 Review 指标追踪
- [ ] 完善 Prompt 质量

### Phase 2: 增强能力
- [ ] Multi-Agent 协作框架
- [ ] Repository Memory
- [ ] RAG 向量存储

### Phase 3: 企业级能力
- [ ] 多租户支持
- [ ] 规则引擎
- [ ] 反馈学习系统

---

## 11. 违规判定标准

| 编号 | 违规行为 | 严重级别 |
|------|---------|---------|
| V-01 | Tool 包含写操作或副作用 | CRITICAL |
| V-02 | 明文存储 API Key | CRITICAL |
| V-03 | 跨 Session 复用 Tool Session | HIGH |
| V-04 | 输出不遵循 JSON Schema | HIGH |
| V-05 | Stage 异常中断 Pipeline | HIGH |
| V-06 | Prompt 硬编码在代码中 | MEDIUM |
| V-07 | Tool 异常未捕获直接抛出 | MEDIUM |