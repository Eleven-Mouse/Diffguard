package com.diffguard.llm;

import com.diffguard.config.ReviewConfig;
import com.diffguard.exception.LlmApiException;
import com.diffguard.agent.StructuredReviewService;
import com.diffguard.model.IssueRecord;
import com.diffguard.model.ReviewOutput;
import com.diffguard.llm.provider.LangChain4jClaudeAdapter;
import com.diffguard.llm.provider.LangChain4jOpenAiAdapter;
import com.diffguard.llm.provider.LlmProvider;
import com.diffguard.llm.provider.TokenTracker;
import com.diffguard.llm.tools.ReviewToolProvider;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;
import com.diffguard.output.ProgressDisplay;
import com.diffguard.prompt.PromptBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LlmClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final int MAX_RETRIES = 3;
    private static final int MAX_SERVER_ERROR_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 15_000;
    private static final long SERVER_ERROR_BASE_DELAY_MS = 5_000;
    private static final int MAX_CONCURRENCY = 3;
    private static final long BATCH_TIMEOUT_SECONDS = 600; // 单批次最大等待时间 10 分钟

    private final LlmProvider provider;
    private final Semaphore concurrencyLimiter = new Semaphore(MAX_CONCURRENCY);
    private final AtomicInteger totalTokensUsed = new AtomicInteger(0);

    private StructuredReviewService structuredService;
    private StructuredReviewService structuredServiceNoTools;
    private ReviewToolProvider toolProvider;
    private ChatModel chatModelForAiServices;
    private final ExecutorService executor;

    /** JSON 重试 prompt（从外部文件懒加载） */
    private static String jsonRetrySystemPrompt;
    private static String jsonRetryUserTemplate;

    public LlmClient(ReviewConfig config) {
        TokenTracker tracker = tokens -> totalTokensUsed.addAndGet(tokens);
        String providerName = config.getLlm().getProvider().toLowerCase();
        ChatModel chatModel;

        this.provider = switch (providerName) {
            case "claude" -> {
                LangChain4jClaudeAdapter adapter = new LangChain4jClaudeAdapter(config.getLlm(), tracker);
                chatModel = adapter.getChatModel();
                yield adapter;
            }
            case "openai" -> {
                LangChain4jOpenAiAdapter adapter = new LangChain4jOpenAiAdapter(config.getLlm(), tracker);
                chatModel = adapter.getChatModel();
                yield adapter;
            }
            default -> throw new IllegalArgumentException("不支持的提供商：" + providerName);
        };

        this.chatModelForAiServices = chatModel;
        StructuredReviewService service = null;
        try {
            service = AiServices.create(StructuredReviewService.class, chatModel);
            log.info("AiServices 结构化输出已启用");
        } catch (Exception e) {
            log.warn("AiServices 初始化失败，将使用手动 JSON 解析：{}", e.getMessage());
        }
        this.structuredService = service;
        this.structuredServiceNoTools = service;
        this.executor = Executors.newFixedThreadPool(MAX_CONCURRENCY);
    }

    public void withTools(ReviewToolProvider toolProvider) {
        this.toolProvider = toolProvider;
        if (chatModelForAiServices != null) {
            try {
                this.structuredService = AiServices.builder(StructuredReviewService.class)
                        .chatModel(chatModelForAiServices)
                        .tools(toolProvider)
                        .build();
                log.info("AiServices 已注册 Tool Use 工具：readFile, listMethods, checkImports");
            } catch (Exception e) {
                log.warn("注册 Tool Use 失败，将使用无工具模式：{}", e.getMessage());
            }
        }
    }

    LlmClient(LlmProvider provider, ReviewConfig config) {
        this.provider = provider;
        this.structuredService = null;
        this.structuredServiceNoTools = null;
        this.chatModelForAiServices = null;
        this.executor = Executors.newFixedThreadPool(MAX_CONCURRENCY);
    }

    public ReviewResult review(List<PromptBuilder.PromptContent> prompts) throws LlmApiException {
        ReviewResult result = new ReviewResult();
        long startTime = System.currentTimeMillis();

        ProgressDisplay.printReviewStart(prompts.size());

        if (prompts.size() <= 1) {
            if (!prompts.isEmpty()) {
                LlmResponse response = callLlmWithRetry(prompts.get(0));
                mergeResponse(response, result);
            }
        } else {
            List<Future<LlmResponse>> futures = new ArrayList<>();
            for (int i = 0; i < prompts.size(); i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    concurrencyLimiter.acquire();
                    try {
                        ProgressDisplay.printBatchProgress(idx + 1, prompts.size());
                        return callLlmWithRetry(prompts.get(idx));
                    } finally {
                        concurrencyLimiter.release();
                    }
                }));
            }

            int failedBatches = 0;
            for (int i = 0; i < futures.size(); i++) {
                try {
                    LlmResponse response = futures.get(i).get(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    mergeResponse(response, result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelRemainingFutures(futures);
                    throw new LlmApiException("并行审查被中断", e);
                } catch (ExecutionException e) {
                    failedBatches++;
                    Throwable cause = e.getCause();
                    log.warn("批次 {}/{} 审查失败：{}", i + 1, futures.size(),
                            cause instanceof LlmApiException lae ? lae.getMessage() : cause.getMessage());
                } catch (TimeoutException e) {
                    failedBatches++;
                    log.warn("批次 {}/{} 审查超时（{}秒），跳过该批次", i + 1, futures.size(), BATCH_TIMEOUT_SECONDS);
                }
            }

            if (failedBatches == futures.size()) {
                throw new LlmApiException("所有批次审查均失败");
            }
            if (failedBatches > 0) {
                log.warn("{}/{} 个批次审查失败，返回部分结果", failedBatches, futures.size());
            }
        }

        result.setTotalTokensUsed(totalTokensUsed.get());
        result.setReviewDurationMs(System.currentTimeMillis() - startTime);

        int totalIssues = result.isRawReport() ? 0 : result.getIssues().size();
        ProgressDisplay.printReviewComplete(totalIssues);
        return result;
    }

    private void cancelRemainingFutures(List<Future<LlmResponse>> futures) {
        for (Future<LlmResponse> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }
    }

    private void mergeResponse(LlmResponse response, ReviewResult result) {
        if (response.isRawText()) {
            String existing = result.getRawReport();
            result.setRawReport(existing == null ? response.getRawText()
                    : existing + "\n\n" + response.getRawText());
        } else {
            for (ReviewIssue issue : response.getIssues()) {
                result.addIssue(issue);
            }
            if (Boolean.TRUE.equals(response.getHasCritical())
                    || response.getIssues().stream().anyMatch(i -> i.getSeverity().shouldBlockCommit())) {
                result.setHasCriticalFlag(true);
            }
        }
        result.setTotalFilesReviewed(result.getTotalFilesReviewed() + 1);
    }

    private LlmResponse callLlmWithRetry(PromptBuilder.PromptContent prompt) throws LlmApiException {
        // Phase 1：优先尝试 AiServices 结构化输出
        if (structuredService != null) {
            LlmResponse structured = tryStructuredOutput(prompt);
            if (structured != null) {
                log.debug("AiServices 结构化输出成功");
                return structured;
            }
            // structured == null: 结构化输出未获得结果（null content 或异常），走 fallback
            log.info("AiServices 未返回有效结果，回退到 provider.call() + 手动解析");
        }

        // Phase 2：回退到 provider.call() + 手动 JSON 解析
        return callLlmWithRetryFallback(prompt);
    }

    /**
     * 通过 AiServices 获取结构化输出，直接使用 PromptContent 的结构化字段。
     */
    private LlmResponse tryStructuredOutput(PromptBuilder.PromptContent prompt) {
        String language = prompt.getLanguage();
        String rules = prompt.getRules();
        String filePath = prompt.getFilePath();
        String diffContent = prompt.getDiffContent();

        // Phase 1: 带 Tool Use 的结构化输出（可能多轮 HTTP 往返）
        try {
            LlmResponse result = doStructuredCall(structuredService, language, rules, filePath, diffContent);
            if (result != null) {
                return result;
            }
            log.warn("AiServices Tool Use 返回 null content，尝试无工具模式");
        } catch (Exception e) {
            if (isRetriableServerError(e) && structuredServiceNoTools != null && structuredServiceNoTools != structuredService) {
                log.warn("Tool Use 路径 {}，降级为无工具结构化输出重试", e.getMessage());
                sleepQuietly(SERVER_ERROR_BASE_DELAY_MS);

                // Phase 2: 降级为无工具结构化输出（单轮 HTTP）
                try {
                    LlmResponse result = doStructuredCall(structuredServiceNoTools, language, rules, filePath, diffContent);
                    if (result != null) {
                        return result;
                    }
                    log.warn("无工具结构化输出返回 null content，回退到手动解析");
                } catch (Exception fallbackEx) {
                    log.warn("无工具结构化输出也失败：{}，回退到手动解析", fallbackEx.getMessage());
                }
                // Tool Use + 无工具都失败，不抛异常，让 callLlmWithRetry 走 fallback
                return null;
            }
            // 非可重试错误（非 502/503/504/429）：记录但不抛异常，让 fallback 处理
            log.warn("AiServices 结构化输出失败（不可重试）：{}，回退到手动解析", e.getMessage());
        }
        return null;
    }

    private LlmResponse doStructuredCall(StructuredReviewService service,
                                          String language, String rules,
                                          String filePath, String diffContent) {
        Result<ReviewOutput> result = service.review(language, rules, filePath, diffContent);

        // 追踪 AiServices 调用的 token 消耗
        if (result.tokenUsage() != null) {
            long tokens = result.tokenUsage().inputTokenCount() + result.tokenUsage().outputTokenCount();
            if (tokens > 0) {
                totalTokensUsed.addAndGet((int) tokens);
            }
        }

        ReviewOutput output = result.content();
        if (output == null) {
            log.warn("AiServices 返回 null content — LLM 可能未输出有效 JSON 结构。Token 已消耗但结果丢失。");
            return null;
        }

        List<ReviewIssue> issues = new ArrayList<>();
        if (output.issues() != null) {
            for (IssueRecord ir : output.issues()) {
                ReviewIssue issue = new ReviewIssue();
                issue.setSeverity(Severity.fromString(ir.severity()));
                issue.setFile(ir.file() != null ? ir.file() : "");
                issue.setLine(ir.line());
                issue.setType(ir.type() != null ? ir.type() : "");
                issue.setMessage(ir.message() != null ? ir.message() : "");
                issue.setSuggestion(ir.suggestion() != null ? ir.suggestion() : "");
                issues.add(issue);
            }
        }

        // 即使 issues 为空，has_critical/summary 等仍有价值，返回非 null
        return new LlmResponse(issues, null, output.has_critical());
    }

    private static boolean isRetriableServerError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.matches(".*\\b(502|503|504|429)\\b.*")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * provider.call() + 手动 JSON 解析路径（fallback）。
     */
    private LlmResponse callLlmWithRetryFallback(PromptBuilder.PromptContent prompt) throws LlmApiException {
        LlmApiException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                ProgressDisplay.printWaiting();
                try {
                    String responseBody = provider.call(prompt.getSystemPrompt(), prompt.getUserPrompt());

                    // 空响应保护：LLM 返回空内容时记录警告但不当作成功
                    if (responseBody == null || responseBody.isBlank()) {
                        log.warn("LLM 返回空响应（attempt {}/{}）", attempt, MAX_RETRIES);
                        if (attempt < MAX_RETRIES) {
                            sleepQuietly(SERVER_ERROR_BASE_DELAY_MS);
                            continue;
                        }
                        return new LlmResponse(List.of(), "LLM 返回空响应", null);
                    }

                    LlmResponse response = LlmResponse.fromContent(responseBody);

                    if (response.isRawText() && response.getRawText() != null && !response.getRawText().isBlank()) {
                        log.info("首次响应非 JSON，发起格式化重试...");
                        try {
                            String retryUserPrompt = String.format(loadJsonRetryUserTemplate(), response.getRawText());
                            String retryResponse = provider.call(loadJsonRetrySystemPrompt(), retryUserPrompt);
                            LlmResponse retryResult = LlmResponse.fromContent(retryResponse);
                            if (!retryResult.isRawText()) {
                                log.info("格式化重试成功，获得有效 JSON 响应");
                                return retryResult;
                            }
                            log.warn("格式化重试仍未返回有效 JSON，使用原始文本");
                        } catch (Exception retryEx) {
                            log.warn("格式化重试失败：{}，使用原始文本", retryEx.getMessage());
                        }
                    }

                    return response;
                } finally {
                    ProgressDisplay.clearWaiting();
                }
            } catch (LlmApiException e) {
                lastException = e;
                int maxAttempts = e.isRateLimitError() ? MAX_RETRIES : MAX_SERVER_ERROR_RETRIES;
                if (e.isRetryable() && attempt < maxAttempts) {
                    int delay;
                    if (e.isRateLimitError()) {
                        delay = (int) RETRY_DELAY_MS;
                    } else {
                        delay = (int) (SERVER_ERROR_BASE_DELAY_MS * (1L << (attempt - 1)));
                    }
                    if (e.isRateLimitError()) {
                        ProgressDisplay.printRateLimitRetry(attempt, MAX_RETRIES, delay / 1000);
                    } else {
                        ProgressDisplay.printServerErrorRetry(attempt, maxAttempts, delay / 1000);
                    }
                    sleepQuietly(delay);
                } else {
                    throw e;
                }
            } catch (IOException | InterruptedException e) {
                throw new LlmApiException("LLM 调用失败：" + e.getMessage(), e);
            }
        }
        throw new LlmApiException("所有重试已耗尽", lastException);
    }

    /**
     * 懒加载 JSON 重试 prompt 模板。
     */
    private static synchronized String loadJsonRetrySystemPrompt() {
        if (jsonRetrySystemPrompt == null) {
            jsonRetrySystemPrompt = loadTemplate("/prompt-templates/json-retry-system.txt",
                    "你是一个格式转换助手。你的唯一任务是将用户给出的代码审查内容转换为严格的 JSON 格式。你必须且仅输出一个合法的 JSON 对象，不得包含任何其他文本。");
        }
        return jsonRetrySystemPrompt;
    }

    private static synchronized String loadJsonRetryUserTemplate() {
        if (jsonRetryUserTemplate == null) {
            jsonRetryUserTemplate = loadTemplate("/prompt-templates/json-retry-user.txt", null);
            if (jsonRetryUserTemplate == null) {
                jsonRetryUserTemplate = "以下是一次代码审查的原始回复内容，请将其转换为以下 JSON 格式：\n\n"
                    + "```json\n"
                    + "{\"has_critical\": boolean, \"summary\": \"总结\", "
                    + "\"issues\": [{\"severity\": \"CRITICAL|WARNING|INFO\", \"file\": \"路径\", "
                    + "\"line\": 行号, \"type\": \"类型\", \"message\": \"描述\", \"suggestion\": \"建议\"}], "
                    + "\"highlights\": [\"亮点\"], \"test_suggestions\": [\"测试建议\"]}\n```\n\n"
                    + "原始回复内容：\n%s";
            }
        }
        return jsonRetryUserTemplate;
    }

    private static String loadTemplate(String resourcePath, String fallback) {
        try (InputStream is = LlmClient.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines().collect(java.util.stream.Collectors.joining("\n"));
            }
        } catch (Exception e) {
            log.warn("加载 prompt 模板失败：{}", resourcePath);
        }
        return fallback;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
