
# DiffGuard

AI 驱动的代码审查工具，在 Git 提交/推送时自动拦截代码变更，通过 LLM 进行智能分析，阻止包含严重问题的代码进入代码库。同时支持 GitHub Webhook 模式，在 Pull Request 时自动进行代码审查并发表评论。

## 特性

- **Git Hook 集成** — 在 `pre-commit` 和 `pre-push` 阶段自动审查代码变更，发现 CRITICAL 级别问题时阻止提交
- **GitHub Webhook 模式** — 监听 PR 事件，自动审查并发表 GFM 格式的评论
- **多 LLM 支持** — 支持 OpenAI（GPT-5 系列）和 Anthropic（Claude）以及兼容的代理服务
- **智能缓存** — 内存 + 磁盘两级缓存，避免重复审查相同 diff
- **并行调用** — 支持多文件并行 LLM 调用，加快审查速度
- **容错重试** — 自动处理限流（429）和服务端错误（5xx），支持 JSON 修复重试
- **自定义 Prompt** — 支持项目级 Prompt 模板覆盖
- **三级配置加载** — 项目目录 > 用户目录 > 内置默认值

## 环境要求

- **Java 21**+
- **Maven 3.8**+
- **Git** 仓库

## 快速开始

### 构建

```bash
cd diffguard
mvn clean package -DskipTests
```

构建产物为 `diffguard/target/diffguard-1.0.0.jar`。

### 设置 API Key

```bash
# OpenAI
export DIFFGUARD_API_KEY="sk-..."

# 或 Anthropic Claude
export DIFFGUARD_API_KEY="sk-ant-..."
```

### 安装 Git Hooks

```bash
java -jar diffguard.jar install            # 安装 pre-commit + pre-push
java -jar diffguard.jar install --pre-commit   # 仅安装 pre-commit
java -jar diffguard.jar install --pre-push     # 仅安装 pre-push
```

安装后，每次 `git commit` 或 `git push` 时会自动触发代码审查。发现 CRITICAL 级别问题时，提交将被阻止。

### 手动审查

```bash
# 审查暂存区变更（等同于 git diff --cached）
java -jar diffguard.jar review --staged

# 审查两个 Git 引用之间的变更
java -jar diffguard.jar review --from HEAD~3 --to HEAD

# 跳过阻止（即使发现严重问题也允许提交）
java -jar diffguard.jar review --staged --force
```

### Webhook 服务器模式

```bash
# 启动 Webhook 服务器
java -jar diffguard.jar server --port 8080

# 指定配置文件
java -jar diffguard.jar server --config /path/to/config.yml
```

在 GitHub 仓库设置中配置 Webhook URL 为 `http://your-server:8080/webhook/github`，DiffGuard 将在 PR 创建和更新时自动审查代码并发表评论。

## CLI 命令

| 命令 | 说明 |
|------|------|
| `diffguard review` | 审查代码变更 |
| `diffguard install` | 安装 Git Hooks |
| `diffguard uninstall` | 卸载 Git Hooks |
| `diffguard server` | 启动 Webhook 服务器 |

### `review` 命令选项

| 选项 | 说明 |
|------|------|
| `--staged` | 审查暂存区变更 |
| `--from <ref>` | 起始 Git 引用 |
| `--to <ref>` | 目标 Git 引用 |
| `--force` | 跳过阻止（忽略 CRITICAL 问题） |
| `--config <path>` | 指定配置文件路径 |
| `--no-cache` | 禁用结果缓存 |

### `install` 命令选项

| 选项 | 说明 |
|------|------|
| `--pre-commit` | 仅安装 pre-commit hook |
| `--pre-push` | 仅安装 pre-push hook |

### `server` 命令选项

| 选项 | 说明 |
|------|------|
| `--port <number>` | 监听端口（默认 8080） |
| `--config <path>` | 指定配置文件路径 |

## 配置

在项目根目录创建 `.review-config.yml` 文件：

```yaml
llm:
  provider: openai              # openai 或 claude
  model: gpt-5                  # 模型名称
  max_tokens: 16384             # 最大响应 token 数
  temperature: 0.3              # 采样温度 (0-2)
  timeout_seconds: 240          # HTTP 超时
  api_key_env: DIFFGUARD_API_KEY # API Key 环境变量名
  # base_url: https://api.your-proxy.com/v1  # 自定义 API 地址

rules:
  enabled:
    - security                  # 安全漏洞（SQL 注入、XSS、硬编码密钥等）
    - bug-risk                  # Bug 风险（空指针、并发、资源泄漏等）
    - code-style                # 代码风格（命名、重复、复杂度等）
    - performance               # 性能问题（不必要的对象创建、低效循环等）
  severity_threshold: info      # 最低报告级别: info / warning / critical

ignore:
  files:                        # 忽略的文件 glob 模式
    - "**/*.generated.java"
    - "**/target/**"
    - "**/node_modules/**"
  patterns:                     # 忽略的 issue 内容正则
    - ".*import statement.*"

review:
  max_diff_files: 20            # 单次审查最大文件数
  max_tokens_per_file: 4000     # 单文件最大 token 数
  language: zh                  # 审查输出语言

# Webhook 服务器配置（仅 server 模式需要）
# webhook:
#   port: 8080
#   secret_env: DIFFGUARD_WEBHOOK_SECRET
#   github_token_env: DIFFGUARD_GITHUB_TOKEN
#   repos:
#     - full_name: "owner/repo"
#       local_path: "/path/to/local/repo"
```

### 配置加载优先级

1. `--config` 命令行参数指定的文件
2. 项目目录下 `.review-config.yml`
3. 用户目录下 `~/.review-config.yml`
4. 内置默认值 `classpath:/application.yml`

### 环境变量

| 变量 | 说明 |
|------|------|
| `DIFFGUARD_API_KEY` | LLM API 密钥 |
| `DIFFGUARD_WEBHOOK_SECRET` | GitHub Webhook 签名密钥（server 模式） |
| `DIFFGUARD_GITHUB_TOKEN` | GitHub Personal Access Token（server 模式，用于发表 PR 评论） |

### 自定义 Prompt 模板

在项目目录下创建 `.diffguard/prompts/system.txt` 和 `.diffguard/prompts/user.txt` 可覆盖内置 Prompt 模板。模板支持以下变量：

| 变量 | 说明 |
|------|------|
| `{{LANGUAGE}}` | 审查输出语言 |
| `{{RULES}}` | 启用的审查规则描述 |
| `{{FILE_PATH}}` | 文件路径 |
| `{{DIFF_CONTENT}}` | Diff 内容 |

## 支持的 LLM 模型

### OpenAI 系列

| 模型 | 说明 |
|------|------|
| `gpt-5` | GPT-5 |
| `gpt-5-codex` | GPT-5 Codex |
| `gpt-5.1` | GPT-5.1 |
| `gpt-5.2` | GPT-5.2 |
| `o3-mini` | o3-mini 推理模型 |
| `o3` | o3 推理模型 |
| `o1` / `o1-mini` | o1 推理模型 |

### Anthropic Claude 系列

| 模型 | 说明 |
|------|------|
| `claude-sonnet-4-6` | Claude Sonnet 4.6（默认） |
| `claude-opus-4-6` | Claude Opus 4.6 |
| `claude-haiku-4-5` | Claude Haiku 4.5 |

支持通过 `base_url` 配置使用兼容 OpenAI 或 Claude API 的代理服务。

## 项目结构

```
diffguard/src/main/java/com/diffguard/
├── DiffGuard.java                 # 程序入口
├── agent/                         # 结构化审查接口
│   ├── StructuredReviewService.java  # LangChain4j AiServices 接口
│   ├── pipeline/                  # 多阶段审查 Pipeline
│   │   ├── MultiStageReviewService.java  # Pipeline 编排器
│   │   ├── DiffSummaryAgent.java  # Stage 1: 变更摘要
│   │   ├── SecurityReviewer.java  # Stage 2: 安全专项审查
│   │   ├── LogicReviewer.java     # Stage 2: 逻辑专项审查
│   │   ├── QualityReviewer.java   # Stage 2: 质量专项审查
│   │   ├── AggregationAgent.java  # Stage 3: 聚合去重
│   │   ├── TargetedReviewResult.java  # 专项审查结构化输出
│   │   └── model/
│   │       ├── DiffSummary.java   # 摘要输出模型
│   │       └── AggregatedReview.java  # 聚合输出模型
├── cli/                           # CLI 命令（picocli）
│   ├── DiffGuardMain.java         # 命令调度
│   ├── ReviewCommand.java         # review 命令
│   ├── InstallCommand.java        # install 命令
│   ├── UninstallCommand.java      # uninstall 命令
│   ├── ServerCommand.java         # server 命令
│   └── VersionProvider.java       # 版本信息
├── config/                        # 配置加载
│   ├── ConfigLoader.java          # 三级配置加载
│   └── ReviewConfig.java          # 配置模型
├── exception/                     # 自定义异常
│   ├── DiffGuardException.java    # 基础异常
│   ├── ConfigException.java       # 配置异常
│   ├── DiffCollectionException.java  # Diff 采集异常
│   ├── LlmApiException.java       # LLM API 异常（含状态码/可重试标记）
│   └── WebhookException.java      # Webhook 异常
├── git/                           # Diff 收集
│   └── DiffCollector.java         # 基于 JGit 的 diff 采集
├── hook/                          # Git Hook 管理
│   └── GitHookInstaller.java      # Hook 安装/卸载
├── llm/                           # LLM 调用层
│   ├── LlmClient.java             # 调用编排（并行、重试、结构化输出）
│   ├── LlmResponse.java           # 响应解析（JSON Object/Array/Text 三级 fallback）
│   ├── provider/                  # LLM Provider 适配
│   │   ├── LlmProvider.java       # Provider 接口
│   │   ├── LangChain4jOpenAiAdapter.java  # OpenAI 适配器（双模型策略）
│   │   ├── LangChain4jClaudeAdapter.java   # Claude 适配器
│   │   ├── TokenTracker.java      # Token 用量回调
│   │   ├── LlmConstants.java      # 共享常量
│   │   ├── ProxyResponseDetector.java  # 代理错误检测
│   │   └── ProviderUtils.java     # 异常转换工具
│   └── tools/                     # LLM Tool Use
│       ├── FileAccessSandbox.java # 文件访问安全沙箱
│       └── ReviewToolProvider.java  # @Tool 注解方法（readFile/listMethods/checkImports）
├── model/                         # 数据模型
│   ├── Severity.java              # CRITICAL / WARNING / INFO
│   ├── ReviewIssue.java           # 单个审查问题
│   ├── ReviewResult.java          # 审查结果聚合
│   ├── ReviewOutput.java          # 结构化输出 record
│   ├── IssueRecord.java           # Issue record（LangChain4j 用）
│   └── DiffFileEntry.java         # Diff 文件数据
├── output/                        # 输出格式化
│   ├── ConsoleFormatter.java      # 终端 ANSI 彩色输出
│   ├── MarkdownFormatter.java     # GitHub PR 评论格式
│   ├── ProgressDisplay.java       # 进度显示/动画
│   ├── StatsFormatter.java        # 统计信息格式化
│   └── AnsiColors.java            # ANSI 颜色常量
├── prompt/                        # Prompt 模板
│   └── PromptBuilder.java         # 模板引擎 + 批处理
├── review/                        # 核心审查服务
│   ├── ReviewService.java         # 审查编排（缓存 + LLM + 结果合并）
│   ├── ReviewCache.java           # Caffeine + 磁盘两级缓存
│   └── ReviewOrchestrator.java    # Webhook 异步审查流水线
├── webhook/                       # Webhook 服务器
│   ├── WebhookServer.java         # Javalin HTTP 服务器
│   ├── WebhookController.java     # 请求处理
│   ├── SignatureVerifier.java     # HMAC-SHA256 签名验证
│   ├── GitHubPayloadParser.java   # PR 事件解析
│   └── GitHubApiClient.java       # GitHub API 评论发布
└── util/                          # 工具类
    ├── JacksonMapper.java         # 共享 ObjectMapper
    └── TokenEstimator.java        # Token 计数（jtokkit）
```

## 开发

### 运行测试

```bash
cd diffguard
mvn test
```

### 完整构建（含测试）

```bash
cd diffguard
mvn clean verify
```

### 主要依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| picocli | 4.7.5 | CLI 框架 |
| JGit | 6.8.0 | Git 操作 |
| Jackson | 2.17.0 | JSON/YAML 处理 |
| jtokkit | 1.0.0 | Token 计数 |
| Caffeine | 3.1.8 | 内存缓存 |
| Javalin | 5.6.3 | HTTP 服务器 |
| JUnit 5 | 5.10.2 | 测试框架 |
| Mockito | 5.11.0 | Mock 框架 |

## License

[MIT](LICENSE)
