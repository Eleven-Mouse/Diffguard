package com.diffguard.infrastructure.llm;

import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.exception.LlmApiException;
import com.diffguard.infrastructure.llm.provider.ClaudeHttpProvider;
import com.diffguard.infrastructure.llm.provider.LlmProvider;
import com.diffguard.infrastructure.llm.provider.OpenAiHttpProvider;
import com.diffguard.infrastructure.llm.provider.TokenTracker;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.infrastructure.output.ProgressDisplay;
import com.diffguard.infrastructure.prompt.JsonRetryPromptLoader;
import com.diffguard.infrastructure.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 调用客户端。
 * <p>
 * 直接 HTTP 调用 OpenAI/Claude API，使用手动 JSON 解析。
 * 支持：重试（rate limit/server error）、格式化重试、批量并发审查。
 */
public class LlmClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final int MAX_RETRIES = 3;
    private static final int MAX_SERVER_ERROR_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 15_000;
    private static final long SERVER_ERROR_BASE_DELAY_MS = 5_000;
    private static final int MAX_CONCURRENCY = 3;

    private final LlmProvider provider;
    private final AtomicInteger totalTokensUsed = new AtomicInteger(0);
    private final BatchReviewExecutor batchExecutor;

    public LlmClient(ReviewConfig config) {
        TokenTracker tracker = tokens -> totalTokensUsed.addAndGet(tokens);
        String providerName = config.getLlm().getProvider().toLowerCase();

        this.provider = switch (providerName) {
            case "claude" -> new ClaudeHttpProvider(config.getLlm(), tracker);
            case "openai" -> new OpenAiHttpProvider(config.getLlm(), tracker);
            default -> throw new IllegalArgumentException("不支持的提供商：" + providerName);
        };
        this.batchExecutor = new BatchReviewExecutor(MAX_CONCURRENCY);
    }

    LlmClient(LlmProvider provider) {
        this.provider = provider;
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
        LlmApiException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                LlmResponse response = attemptSingleCall(prompt);

                if (response.isRawText() && response.getRawText() != null && !response.getRawText().isBlank()) {
                    response = attemptFormatRetry(response);
                }

                return response;
            } catch (LlmApiException e) {
                lastException = e;
                int errorMaxAttempts = e.isRateLimitError() ? MAX_RETRIES : MAX_SERVER_ERROR_RETRIES;
                if (e.isRetryable() && attempt < errorMaxAttempts + 1) {
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

    private LlmResponse attemptSingleCall(PromptBuilder.PromptContent prompt)
            throws LlmApiException, IOException, InterruptedException {
        ProgressDisplay.startSpinner();
        try {
            String responseBody = provider.call(prompt.getSystemPrompt(), prompt.getUserPrompt());

            if (responseBody == null || responseBody.isBlank()) {
                log.warn("LLM 返回空响应");
                return new LlmResponse(List.of(), "LLM 返回空响应", null);
            }

            return LlmResponse.fromContent(responseBody);
        } finally {
            ProgressDisplay.stopSpinner();
        }
    }

    private LlmResponse attemptFormatRetry(LlmResponse rawResponse) {
        log.info("首次响应非 JSON，发起格式化重试...");
        try {
            String retryUserPrompt = String.format(
                    JsonRetryPromptLoader.loadJsonRetryUserTemplate(), rawResponse.getRawText());
            String retryResponse = provider.call(
                    JsonRetryPromptLoader.loadJsonRetrySystemPrompt(), retryUserPrompt);
            LlmResponse retryResult = LlmResponse.fromContent(retryResponse);
            if (!retryResult.isRawText()) {
                log.info("格式化重试成功，获得有效 JSON 响应");
                return retryResult;
            }
            log.warn("格式化重试仍未返回有效 JSON，使用原始文本");
        } catch (Exception retryEx) {
            log.warn("格式化重试失败：{}，使用原始文本", retryEx.getMessage());
        }
        return rawResponse;
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
        try {
            provider.close();
        } catch (Exception e) {
            log.warn("关闭 LlmProvider 失败", e);
        }
        batchExecutor.close();
    }
}
