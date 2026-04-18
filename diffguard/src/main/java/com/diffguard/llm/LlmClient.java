package com.diffguard.llm;

import com.diffguard.config.ReviewConfig;
import com.diffguard.exception.LlmApiException;
import com.diffguard.agent.core.StructuredReviewService;
import com.diffguard.llm.provider.LangChain4jClaudeAdapter;
import com.diffguard.llm.provider.LangChain4jOpenAiAdapter;
import com.diffguard.llm.provider.LlmProvider;
import com.diffguard.llm.provider.TokenTracker;
import com.diffguard.llm.tools.UnifiedToolProvider;
import com.diffguard.model.ReviewResult;
import com.diffguard.output.ProgressDisplay;
import com.diffguard.prompt.JsonRetryPromptLoader;
import com.diffguard.prompt.PromptBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class LlmClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final int MAX_RETRIES = 3;
    private static final int MAX_SERVER_ERROR_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 15_000;
    private static final long SERVER_ERROR_BASE_DELAY_MS = 5_000;
    private static final int MAX_CONCURRENCY = 3;

    private final LlmProvider provider;
    private final AtomicInteger totalTokensUsed = new AtomicInteger(0);

    private StructuredOutputHandler structuredOutputHandler;
    private UnifiedToolProvider toolProvider;
    private ChatModel chatModelForAiServices;
    private final BatchReviewExecutor batchExecutor;


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
        this.structuredOutputHandler = service != null
                ? new StructuredOutputHandler(service, service, totalTokensUsed)
                : null;
        this.batchExecutor = new BatchReviewExecutor(MAX_CONCURRENCY);
    }

    public void withTools(UnifiedToolProvider toolProvider) {
        this.toolProvider = toolProvider;
        if (chatModelForAiServices != null) {
            try {
                StructuredReviewService withTools = AiServices.builder(StructuredReviewService.class)
                        .chatModel(chatModelForAiServices)
                        .tools(toolProvider)
                        .build();
                StructuredReviewService noTools = structuredOutputHandler != null
                        ? structuredOutputHandler.getStructuredServiceNoTools() : null;
                this.structuredOutputHandler = new StructuredOutputHandler(withTools, noTools, totalTokensUsed);
                log.info("AiServices 已注册 Tool Use 工具：readFile, listMethods, checkImports");
            } catch (Exception e) {
                log.warn("注册 Tool Use 失败，将使用无工具模式：{}", e.getMessage());
            }
        }
    }

    LlmClient(LlmProvider provider, ReviewConfig config) {
        this.provider = provider;
        this.structuredOutputHandler = null;
        this.chatModelForAiServices = null;
        this.batchExecutor = new BatchReviewExecutor(MAX_CONCURRENCY);
    }

    public ReviewResult review(List<PromptBuilder.PromptContent> prompts) throws LlmApiException {
        ReviewResult result;
        long startTime = System.currentTimeMillis();

        ProgressDisplay.printReviewStart(prompts.size());

        if (prompts.size() <= 1) {
            result = new ReviewResult();
            if (!prompts.isEmpty()) {
                LlmResponse response = callLlmWithRetry(prompts.get(0));
                batchExecutor.mergeResponse(response, result);
            }
        } else {
            result = batchExecutor.executeBatch(prompts, prompt -> {
                if (toolProvider != null) {
                    toolProvider.resetCallCount();
                }
                try {
                    return callLlmWithRetry(prompt);
                } catch (LlmApiException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        result.setTotalTokensUsed(totalTokensUsed.get());
        result.setReviewDurationMs(System.currentTimeMillis() - startTime);

        int totalIssues = result.isRawReport() ? 0 : result.getIssues().size();
        ProgressDisplay.printReviewComplete(totalIssues);
        return result;
    }

    private LlmResponse callLlmWithRetry(PromptBuilder.PromptContent prompt) throws LlmApiException {
        // Phase 1：优先尝试 AiServices 结构化输出
        if (structuredOutputHandler != null) {
            int tokensBefore = totalTokensUsed.get();
            ProgressDisplay.startSpinner();
            try {
                LlmResponse structured = structuredOutputHandler.tryStructuredOutput(prompt);
                if (structured != null) {
                    log.debug("AiServices 结构化输出成功");
                    return structured;
                }
            } finally {
                ProgressDisplay.stopSpinner();
            }
            boolean tokensConsumed = totalTokensUsed.get() > tokensBefore;
            if (tokensConsumed) {
                log.warn("Phase 1 已消耗 Token 但未获得有效结果，Phase 2 仅尝试 1 次");
            }
            log.info("AiServices 未返回有效结果，回退到 provider.call() + 手动解析");
            return callLlmWithRetryFallback(prompt, tokensConsumed);
        }

        // Phase 2：回退到 provider.call() + 手动 JSON 解析
        return callLlmWithRetryFallback(prompt, false);
    }

    private LlmResponse callLlmWithRetryFallback(PromptBuilder.PromptContent prompt, boolean reduceRetries) throws LlmApiException {
        LlmApiException lastException = null;
        int maxAttempts = reduceRetries ? 1 : MAX_RETRIES;

        log.debug("Phase 2 调用: systemPrompt长度={}, userPrompt长度={}, reduceRetries={}",
                prompt.getSystemPrompt().length(), prompt.getUserPrompt().length(), reduceRetries);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ProgressDisplay.startSpinner();
                try {
                    String responseBody = provider.call(prompt.getSystemPrompt(), prompt.getUserPrompt());

                    if (responseBody == null || responseBody.isBlank()) {
                        log.warn("LLM 返回空响应（attempt {}/{}）", attempt, maxAttempts);
                        if (attempt < maxAttempts) {
                            sleepQuietly(SERVER_ERROR_BASE_DELAY_MS);
                            continue;
                        }
                        return new LlmResponse(List.of(), "LLM 返回空响应", null);
                    }

                    LlmResponse response = LlmResponse.fromContent(responseBody);

                    if (response.isRawText() && response.getRawText() != null && !response.getRawText().isBlank()) {
                        if (!reduceRetries) {
                            log.info("首次响应非 JSON，发起格式化重试...");
                            try {
                                String retryUserPrompt = String.format(JsonRetryPromptLoader.loadJsonRetryUserTemplate(), response.getRawText());
                                String retryResponse = provider.call(JsonRetryPromptLoader.loadJsonRetrySystemPrompt(), retryUserPrompt);
                                LlmResponse retryResult = LlmResponse.fromContent(retryResponse);
                                if (!retryResult.isRawText()) {
                                    log.info("格式化重试成功，获得有效 JSON 响应");
                                    return retryResult;
                                }
                                log.warn("格式化重试仍未返回有效 JSON，使用原始文本");
                            } catch (Exception retryEx) {
                                log.warn("格式化重试失败：{}，使用原始文本", retryEx.getMessage());
                            }
                        } else {
                            log.info("Phase 1 已消耗 Token，跳过格式化重试，直接使用原始文本");
                        }
                    }

                    return response;
                } finally {
                    ProgressDisplay.stopSpinner();
                }
            } catch (LlmApiException e) {
                lastException = e;
                int errorMaxAttempts = e.isRateLimitError() ? MAX_RETRIES : MAX_SERVER_ERROR_RETRIES;
                if (e.isRetryable() && attempt < errorMaxAttempts) {
                    int delay;
                    if (e.isRateLimitError()) {
                        delay = (int) RETRY_DELAY_MS;
                    } else {
                        delay = (int) (SERVER_ERROR_BASE_DELAY_MS * (1L << (attempt - 1)));
                    }
                    if (e.isRateLimitError()) {
                        ProgressDisplay.printRateLimitRetry(attempt, MAX_RETRIES, delay / 1000);
                    } else {
                        ProgressDisplay.printServerErrorRetry(attempt, errorMaxAttempts, delay / 1000);
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

    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        batchExecutor.close();
    }
}
