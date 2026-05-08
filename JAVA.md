# DiffGuard Java Gateway 工程规范

> 本文档为 DiffGuard Java Gateway 服务的工程约束规范。所有 Java 代码提交必须遵守。
> Code Review 中发现违反本文档的代码必须打回。

---

## 1. 技术栈约束

### 1.1 锁定版本

| 组件 | 版本 | 用途 | 禁止替换 |
|------|------|------|---------|
| Java | 21 | 运行时 | 禁止降级到 17 |
| Picocli | 4.7.5 | CLI 框架 | - |
| Javalin | 5.6.3 | HTTP Server | 禁止换用 Spring |
| JavaParser | 3.26.3 | AST 解析 | - |
| JGit | 6.8.0 | Git 操作 | 禁止直接 shell out git 命令（fetch 除外） |
| Jackson | 2.17.0 | JSON/YAML | 禁止 Gson / Fastjson |
| Caffeine | 3.1.8 | 本地缓存 | - |
| Resilience4j | 2.2.0 | 熔断/限流/重试 | 禁止 Hystrix |
| HikariCP | 5.1.0 | 连接池 | - |
| Jedis | 5.1.3 | Redis 客户端 | - |
| RabbitMQ Client | 5.21.0 | 消息队列 | - |
| Micrometer | 1.13.0 | 指标采集 | - |
| JUnit 5 | 5.10.2 | 测试 | 禁止 JUnit 4 |
| Mockito | 5.11.0 | Mock | - |

### 1.2 禁止引入

- 禁止引入 Spring / Spring Boot 框架（项目基于 Javalin 轻量架构）
- 禁止引入 `fastjson`（安全漏洞）
- 禁止引入 `commons-lang`（使用 JDK 21 内置 String 方法）
- 禁止引入 `lombok`（保持代码可读性，IDE 生成 boilerplate）

---

## 2. 分层架构约束

### 2.1 六边形架构

```
com.diffguard
├── cli/                    # CLI 入口层
│   └── commands/           # Picocli 命令
├── adapter/                # 适配器层（入站）
│   ├── webhook/            # GitHub Webhook 适配器
│   └── toolserver/         # Tool Server 适配器（Python 回调入口）
├── service/                # 应用服务层（编排）
│   ├── ReviewApplicationService    # CLI 模式编排
│   ├── ReviewOrchestrator          # Webhook 模式 10 步编排
│   └── ReviewEngineFactory         # 引擎选择工厂
├── domain/                 # 领域层（核心业务逻辑）
│   ├── review/             # 审查引擎接口 + 简单模式实现
│   ├── agent/              # Agent 工具体系 + Python 桥接
│   │   ├── python/         #   PythonReviewEngine
│   │   └── tools/          #   FileAccessSandbox + Tool 定义
│   ├── ast/                # AST 分析 + SPI 多语言扩展
│   ├── codegraph/          # 代码知识图谱 + BFS 影响分析
│   ├── coderag/            # 代码 RAG（切片 + 向量 + 搜索）
│   └── rules/              # 静态规则引擎
├── infrastructure/         # 基础设施层（技术实现）
│   ├── llm/                # LLM HTTP 客户端 + Provider
│   ├── messaging/          # RabbitMQ 发布/消费
│   ├── persistence/        # MySQL Repository
│   ├── config/             # 三层配置加载
│   ├── git/                # JGit Diff 收集 + Git Hook
│   ├── resilience/         # Resilience4j 封装
│   ├── observability/      # Micrometer Metrics
│   ├── output/             # Terminal UI + Markdown 格式化
│   └── prompt/             # Prompt 模板引擎
└── DiffGuard.java          # 主入口
```

### 2.2 依赖规则

```
cli → service → domain ← infrastructure
adapter → service → domain
                    ↑
         domain 不依赖任何外层
```

**必须：**
- `domain` 层零外部依赖（仅依赖 JDK + 领域接口）
- `domain` 层禁止 import `infrastructure` / `service` / `adapter` / `cli` 的任何类
- `infrastructure` 实现 `domain` 定义的接口（依赖倒置）
- `service` 层仅做编排，不包含业务逻辑

**禁止：**
- 禁止 `domain` 层直接 import Jackson / JGit / Javalin 等外部库
- 禁止 `adapter` 层直接调用 `infrastructure` 层（必须通过 `service` 中转）
- 禁止跨层 new 对象（使用 Factory 或构造器注入）

---

## 3. AI 调用规范

### 3.1 Java → Python 调用

```java
// 正确：通过 PythonReviewEngine 封装
ReviewEngine engine = ReviewEngineFactory.create(EngineType.MULTI_AGENT, config, ...);
ReviewResult result = engine.review(diffEntries, projectDir);

// 禁止：直接构造 HTTP 请求调用 Python
```

**约束：**
- `PythonReviewEngine` 必须包裹在 `ResilienceService` 熔断器中
- `PythonAgentClient` 的目标 URL 必须从 `DIFFGUARD_AGENT_URL` 环境变量解析，禁止硬编码
- 请求体中 `llm_config.api_key_env` 传递环境变量名，Python 侧自行解析，禁止传递明文 key
- 超时上限 300s（与 `ReviewOrchestrator.TASK_TIMEOUT_SECONDS` 一致）

### 3.2 Java → LLM 直接调用（Simple 模式）

```java
// LlmClient 调用链
LlmClient client = new LlmClient(config);
ReviewResult result = client.review(promptContents);
client.close();
```

**约束：**
- `LlmClient` 必须实现 `AutoCloseable`，调用方必须使用 try-with-resources
- 单次调用超时由 `LlmProvider` 内部控制
- 批量调用并发度上限 `MAX_CONCURRENCY = 3`
- 重试策略见 AGENT.md §5.2
- 响应非 JSON 时触发 `JsonRetryPromptLoader` 格式化重试（最多 1 次）

### 3.3 熔断与降级

```java
// Resilience4j Circuit Breaker 配置
resilience.executeAgentCall(() -> pythonReviewEngine.review(...));
```

**降级链：**
```
MULTI_AGENT (Python) → PIPELINE (Python) → SIMPLE (Java直连LLM) → 静态规则扫描
```

**约束：**
- 熔断器 OPEN 状态时必须直接走降级路径，禁止等待
- 降级发生时必须记录 WARN 级别日志 + Metrics 计数
- RabbitMQ 不可用时自动降级为 HTTP 同步调用

---

## 4. Domain 模型规范

### 4.1 核心领域对象

| 类 | 职责 | 不可变 |
|----|------|--------|
| `DiffFileEntry` | 单文件 diff 数据（path, content, changeType, tokenCount） | 是 |
| `ReviewResult` | 审查结果（issues, summary, hasCritical） | 是 |
| `ReviewIssue` | 单个问题（file, line, severity, type, message, suggestion） | 是 |
| `Severity` | 严重级别枚举（CRITICAL, WARNING, INFO） | - |
| `CodeGraph` | 代码知识图谱（nodes + edges + BFS 查询） | 构造后只读 |

**约束：**
- 领域对象必须使用 `record` 或不可变类（所有字段 `final`）
- 禁止在领域对象中包含序列化/反序列化逻辑（属于 infrastructure）
- 禁止在领域对象中包含日志框架依赖

### 4.2 接口定义

```java
// 审查引擎接口（domain 层定义）
public interface ReviewEngine extends AutoCloseable {
    ReviewResult review(List<DiffFileEntry> entries, Path projectDir) throws ReviewException;
}

// LLM Provider 接口（domain 层定义）
public interface LlmProvider {
    LlmResponse call(String systemPrompt, String userPrompt) throws LlmException;
}

// 静态规则接口（domain 层定义）
public interface StaticRule {
    String name();
    List<ReviewIssue> check(DiffFileEntry entry);
}
```

**约束：**
- 接口必须定义在 `domain` 层
- 实现类必须在 `infrastructure` 或 `domain` 内部包中
- 接口方法签名禁止使用外部库类型（如 Jackson `JsonNode`）

---

## 5. AST 分析规范

### 5.1 AST 架构

```
ASTEnricher (Facade)
├── ASTAnalyzer          # JavaParser AST 解析
├── ASTContextBuilder    # AST + Diff → 上下文字符串
├── ASTCache             # Caffeine 缓存 (path + contentHash → AST)
└── ASTLanguageProvider  # SPI 多语言扩展点
```

### 5.2 AST Enrichment 约束

**必须：**
- `ASTEnricher.enrich()` 对非 Java 文件必须透传，不抛异常
- AST 解析失败时必须返回原始 `DiffFileEntry`，不阻塞 Pipeline
- AST 分析结果必须缓存（key = filePath + contentHash）
- Enrichment 产物格式：`[AST Context]...[/AST Context]` 前置于 diff content

**禁止：**
- 禁止 AST 分析修改原始 diff content（只能 prepend）
- 禁止 AST 分析抛出 checked exception 到调用方

### 5.3 SPI 扩展

新增语言 AST 支持必须通过 SPI 机制：
1. 实现 `ASTLanguageProvider` 接口
2. 注册到 `META-INF/services/com.diffguard.domain.ast.ASTLanguageProvider`
3. 在 `ASTEnricher` 中通过 SPI loader 自动发现

---

## 6. CodeGraph 规范

### 6.1 图模型

```
节点类型: FILE / CLASS / INTERFACE / METHOD
边类型:   CALLS / EXTENDS / IMPLEMENTS / IMPORTS / CONTAINS
```

**约束：**
- `CodeGraph` 构造完成后必须只读（线程安全查询）
- 查询方法返回不可变视图（`Collections.unmodifiableList`）
- BFS 算法必须有 `maxDepth` 参数防止无限遍历

### 6.2 影响分析

`computeImpactSet(changedNodeIds, maxDepth)` 通过 BFS 反向遍历 incoming edges 计算 blast radius。

**约束：**
- 默认 `maxDepth` 不超过 5
- 返回的节点集不可修改

---

## 7. Tool Server 规范

### 7.1 端点设计

| 路径 | 功能 | 认证 |
|------|------|------|
| `POST /api/v1/tools/session` | 创建工具会话 | Session-based |
| `DELETE /api/v1/tools/session` | 销毁会话 | X-Session-Id |
| `POST /api/v1/tools/file-content` | 读取文件 | X-Session-Id |
| `POST /api/v1/tools/diff-context` | Diff 上下文 | X-Session-Id |
| `POST /api/v1/tools/method-definition` | 方法定义 | X-Session-Id |
| `POST /api/v1/tools/call-graph` | 调用图 | X-Session-Id |
| `POST /api/v1/tools/related-files` | 关联文件 | X-Session-Id |
| `POST /api/v1/tools/semantic-search` | 语义搜索 | X-Session-Id |

### 7.2 安全约束

- `FileAccessSandbox` 必须在每次文件读取前执行双重校验：
  1. 路径规范化 + `projectRoot` 包含校验（防 `../` 穿越）
  2. `allowedFiles` 白名单校验
- Session TTL ≤ 10 分钟
- Session ID 使用 UUID v4

---

## 8. DTO / VO / Entity 规范

### 8.1 对象分类

| 类型 | 命名后缀 | 位置 | 不可变 | 用途 |
|------|---------|------|--------|------|
| Domain Object | 无后缀 | `domain/` | 是 | 核心业务模型 |
| DTO | `Request` / `Response` | `adapter/` 或 `infrastructure/` | 是 | 跨层/跨服务传输 |
| Entity | 无后缀 | `infrastructure/persistence/` | - | 数据库映射 |
| Config | `Config` | `infrastructure/config/` | 是 | 配置对象 |

### 8.2 转换规则

```
Domain ←→ DTO: 在 adapter 层转换，使用手动映射（禁止 MapStruct）
Domain ←→ Entity: 在 infrastructure/persistence 层转换
```

**禁止：**
- 禁止 Domain 对象依赖 DTO / Entity
- 禁止 DTO 直接暴露 Domain 内部状态（必须构造新对象）
- 禁止在转换逻辑中包含业务逻辑

---

## 9. API 设计规范

### 9.1 RESTful 约束

- URL 使用 kebab-case：`/api/v1/review-requests`
- HTTP 方法语义：GET 只读、POST 创建/触发、DELETE 销毁
- 响应必须包含 `Content-Type: application/json`

### 9.2 错误码体系

```json
{
  "error": {
    "code": "DIFFGUARD_XXXXX",
    "message": "Human-readable description",
    "request_id": "uuid"
  }
}
```

| 错误码 | 含义 | HTTP Status |
|-------|------|------------|
| `DIFFGUARD_00001` | 内部服务异常 | 500 |
| `DIFFGUARD_10001` | 无效请求参数 | 400 |
| `DIFFGUARD_10002` | Webhook 签名校验失败 | 401 |
| `DIFFGUARD_10003` | 速率限制 | 429 |
| `DIFFGUARD_20001` | Agent 服务不可用 | 502 |
| `DIFFGUARD_20002` | LLM 调用超时 | 504 |
| `DIFFGUARD_30001` | Tool Session 不存在或已过期 | 410 |

---

## 10. 并发与性能规范

### 10.1 线程池

```java
// ReviewOrchestrator 线程池配置
ExecutorService executor = new ThreadPoolExecutor(
    1,                          // corePoolSize
    4,                          // maxPoolSize
    60L, TimeUnit.SECONDS,      // keepAlive
    new ArrayBlockingQueue<>(10), // 有界队列
    new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：调用者线程执行
);
```

**约束：**
- 禁止使用 `Executors.newCachedThreadPool()`（无界线程）
- 禁止使用 `Executors.newFixedThreadPool()` 无界队列重载
- 所有线程池必须设置有界队列
- 拒绝策略使用 `CallerRunsPolicy`（背压）或自定义策略（记录 Metrics）

### 10.2 异步调用

- `ReviewOrchestrator.processAsync()` 使用线程池提交 + `ScheduledExecutorService` 超时控制
- `LlmClient` 批量调用使用 `BatchReviewExecutor`，并发度 ≤ 3
- RabbitMQ 异步通道：Java 发布任务 → Python 消费 → 结果回传 → Java 轮询结果

### 10.3 缓存

| 缓存 | 实现 | Key | TTL |
|------|------|-----|-----|
| AST 解析结果 | Caffeine | filePath + contentHash | 无过期（容量淘汰） |
| Review 结果 | `ReviewCache` | config hash + diff hash | 可配置 |
| 向量 Embedding | Redis / InMemory | code slice hash | 无过期 |

---

## 11. 日志与可观测性

### 11.1 日志规范

```java
// 必须：包含 request_id 和关键上下文
log.info("Review completed: requestId={}, mode={}, files={}, issues={}, duration={}ms",
    requestId, mode, fileCount, issueCount, durationMs);

// 禁止：日志中包含密钥、Token
log.debug("LLM response received");  // ✅
log.debug("API key: {}", apiKey);     // ❌ 禁止
```

**约束：**
- 所有日志必须使用 SLF4J + Logback
- 日志格式统一：`[traceId] [thread] LEVEL class - message`
- 生产环境日志级别：INFO；调试时可切 DEBUG
- 异常日志必须包含完整堆栈（`log.error("msg", exception)`）

### 11.2 traceId 传播

- Webhook 请求入口生成 `traceId`（UUID）
- `traceId` 通过 `MDC` (Mapped Diagnostic Context) 传递
- Java → Python 调用时通过 HTTP Header `X-Trace-Id` 传播
- RabbitMQ 消息通过 message header 传播

### 11.3 Metrics

通过 Micrometer + Prometheus 暴露指标，端口 9091。

| 指标 | 类型 | 标签 |
|------|------|------|
| `diffguard_review_total` | Counter | `status`, `mode` |
| `diffguard_review_duration_seconds` | Timer | `mode` |
| `diffguard_issues_total` | Counter | `severity`, `agent`, `source` |
| `diffguard_llm_tokens_total` | Counter | `provider`, `type` |
| `diffguard_circuit_breaker_state` | Gauge | `provider` |

---

## 12. 数据库规范

### 12.1 Schema

```sql
-- 禁止修改以下表结构，新增字段必须通过 migration
review_task   (id, request_id, status, mode, trigger_type, ...)
review_result  (id, task_id, file_path, line_number, severity, issue_type, agent_name, ...)
review_stats   (id, date, total_reviews, total_issues, ...)
```

### 12.2 Repository 约束

- 使用 HikariCP 连接池，连接数上限 ≤ 10
- Repository 实现类位于 `infrastructure/persistence/`
- 禁止在 Repository 中包含业务逻辑
- 所有写操作必须使用事务（connection.setAutoCommit(false)）
- 读取操作使用 try-with-resources 确保 ResultSet / Statement 关闭

### 12.3 幂等性

- `review_task.request_id` 列必须设置 UNIQUE 约束
- INSERT 时使用 `ON DUPLICATE KEY UPDATE` 处理重复提交

---

## 13. 测试规范

### 13.1 测试要求

| 层级 | 测试类型 | 覆盖率要求 |
|------|---------|-----------|
| `domain/` | 单元测试（纯逻辑） | ≥ 80% |
| `service/` | 集成测试（Mock 外部依赖） | ≥ 70% |
| `adapter/` | 集成测试（HTTP Mock） | ≥ 60% |
| `infrastructure/` | 单元测试 + 集成测试 | ≥ 60% |

### 13.2 测试约束

- 测试框架：JUnit 5 + Mockito
- 禁止在单元测试中发起真实 HTTP 请求（Mock HTTP Client）
- 禁止在 CI 中依赖外部服务（LLM API、MySQL）—— 使用 Mock 或内存实现
- 测试类命名：`{被测类}Test.java`，放在 mirror package 结构下
- 每个 `StaticRule` 实现必须有对应的单元测试覆盖 positive/negative case

### 13.3 覆盖率

- JaCoCo 配置已集成（`pom.xml`）
- CI 流水线必须执行 `mvn -B verify` 并检查覆盖率阈值
- 覆盖率报告作为 CI Artifact 上传

---

## 14. 构建与发布

### 14.1 构建

```bash
cd services/gateway
mvn clean verify                  # 编译 + 测试 + 覆盖率
mvn package -DskipTests           # 打包 fat JAR (跳过测试，CI 外使用)
```

**产物：** `target/diffguard-1.0.0.jar` (shade plugin fat JAR)

### 14.2 Docker

```dockerfile
FROM eclipse-temurin:21-jre
# 禁止使用 JDK 镜像（减小攻击面）
```

### 14.3 配置管理

```
优先级（低→高）:
  内置默认值 → 项目 application.yml → ~/.diffguard/config.yml → 环境变量
```

所有密钥必须通过环境变量注入，配置文件中只写 env var name。
