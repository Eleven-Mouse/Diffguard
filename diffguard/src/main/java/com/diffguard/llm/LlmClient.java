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
    private static final long RETRY_DELAY_MS = 15_000;
    private static final long SERVER_ERROR_BASE_DELAY_MS = 5_000;
    private static final int MAX_CONCURRENCY = 3;

    private final LlmProvider provider;
    private final Semaphore concurrencyLimiter = new Semaphore(MAX_CONCURRENCY);
    private final AtomicInteger totalTokensUsed = new AtomicInteger(0);

    private StructuredReviewService structuredService;
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

            for (Future<LlmResponse> future : futures) {
                try {
                    LlmResponse response = future.get();
                    mergeResponse(response, result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelRemainingFutures(futures);
                    throw new LlmApiException("并行审查被中断", e);
                } catch (ExecutionException e) {
                    cancelRemainingFutures(futures);
                    Throwable cause = e.getCause();
                    if (cause instanceof LlmApiException lae) throw lae;
                    throw new LlmApiException("并行审查失败：" + cause.getMessage(), cause);
                }
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
        // Phase 2：优先尝试 AiServices 结构化输出
        if (structuredService != null) {
            try {
                LlmResponse structured = tryStructuredOutput(prompt);
                if (structured != null) {
                    log.debug("AiServices 结构化输出成功");
                    return structured;
                }
            } catch (Exception e) {
                log.warn("AiServices 结构化输出失败，回退到手动解析：{}", e.getMessage());
            }
        }

        // 回退：provider.call() + 手动 JSON 解析
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

        Result<ReviewOutput> result = structuredService.review(language, rules, filePath, diffContent);
        ReviewOutput output = result.content();

        if (output == null) {
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
        return new LlmResponse(issues, null, output.has_critical());
    }

    /**
     * provider.call() + 手动 JSON 解析路径（fallback）。
     */
    private LlmResponse callLlmWithRetryFallback(PromptBuilder.PromptContent prompt) throws LlmApiException {
        LlmApiException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Thread animator = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        ProgressDisplay.printWaiting();
                        try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                    }
                });
                animator.setDaemon(true);
                animator.start();

                try {
                    String responseBody = provider.call(prompt.getSystemPrompt(), prompt.getUserPrompt());
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
                    animator.interrupt();
                    animator.join(300);
                    ProgressDisplay.clearWaiting();
                }
            } catch (LlmApiException e) {
                lastException = e;
                if (e.isRetryable() && attempt < MAX_RETRIES) {
                    int delay;
                    if (e.isRateLimitError()) {
                        delay = (int) RETRY_DELAY_MS;
                    } else {
                        delay = (int) (SERVER_ERROR_BASE_DELAY_MS * (1L << (attempt - 1)));
                    }
                    if (e.isRateLimitError()) {
                        ProgressDisplay.printRateLimitRetry(attempt, MAX_RETRIES, delay / 1000);
                    } else {
                        ProgressDisplay.printServerErrorRetry(attempt, MAX_RETRIES, delay / 1000);
                    }
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
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
