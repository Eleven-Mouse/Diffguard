package com.diffguard.llm;

import com.diffguard.config.ReviewConfig;
import com.diffguard.exception.LlmApiException;
import com.diffguard.llm.provider.ClaudeProvider;
import com.diffguard.llm.provider.LlmProvider;
import com.diffguard.llm.provider.OpenAiProvider;
import com.diffguard.llm.provider.TokenTracker;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.output.ProgressDisplay;
import com.diffguard.prompt.PromptBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LlmClient {


    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 15_000;

    /** 并发调用时的最大并行度 */
    private static final int MAX_CONCURRENCY = 3;

    private final LlmProvider provider;
    private final HttpClient httpClient;
    private final Semaphore concurrencyLimiter = new Semaphore(MAX_CONCURRENCY);
    private int totalTokensUsed = 0;

    public LlmClient(ReviewConfig config) {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(config.getLlm().getTimeoutSeconds()))
                .build();

        TokenTracker tracker = tokens -> totalTokensUsed += tokens;
        String providerName = config.getLlm().getProvider().toLowerCase();
        this.provider = switch (providerName) {
            case "claude" -> new ClaudeProvider(config.getLlm(), httpClient, tracker);
            case "openai" -> new OpenAiProvider(config.getLlm(), httpClient, tracker);
            default -> throw new IllegalArgumentException("不支持的提供商：" + providerName);
        };
    }

    public ReviewResult review(List<PromptBuilder.PromptContent> prompts) throws LlmApiException {
        ReviewResult result = new ReviewResult();
        long startTime = System.currentTimeMillis();

        ProgressDisplay.printReviewStart(prompts.size());

        if (prompts.size() <= 1) {
            // 单批次：无需并行开销
            if (!prompts.isEmpty()) {
                LlmResponse response = callLlmWithRetry(prompts.get(0));
                mergeResponse(response, result);
            }
        } else {
            // 多批次：并行调用，按顺序合并结果保证稳定性
            ExecutorService executor = Executors.newFixedThreadPool(
                    Math.min(prompts.size(), MAX_CONCURRENCY));
            try {
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
                        throw new LlmApiException("并行审查被中断", e);
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof LlmApiException lae) throw lae;
                        throw new LlmApiException("并行审查失败：" + cause.getMessage(), cause);
                    }
                }
            } finally {
                executor.shutdown();
            }
        }

        result.setTotalTokensUsed(totalTokensUsed);
        result.setReviewDurationMs(System.currentTimeMillis() - startTime);

        int totalIssues = result.isRawReport() ? 0 : result.getIssues().size();
        ProgressDisplay.printReviewComplete(totalIssues);
        return result;
    }

    /**
     * 将单个 LLM 响应合并到聚合结果中。
     */
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

    /** JSON 重试时的系统提示词 */
    private static final String JSON_RETRY_SYSTEM_PROMPT =
            "你是一个格式转换助手。你的唯一任务是将用户给出的代码审查内容转换为严格的 JSON 格式。"
            + "你必须且仅输出一个合法的 JSON 对象，不得包含任何其他文本。";

    /** JSON 重试时的用户提示词模板 */
    private static final String JSON_RETRY_USER_TEMPLATE =
            "以下是一次代码审查的原始回复内容，请将其转换为以下 JSON 格式：\n\n"
            + "```json\n"
            + "{\n"
            + "  \"has_critical\": boolean,\n"
            + "  \"summary\": \"总结\",\n"
            + "  \"issues\": [{\"severity\": \"CRITICAL|WARNING|INFO\", \"file\": \"路径\", \"line\": 行号, \"type\": \"类型\", \"message\": \"描述\", \"suggestion\": \"建议\"}],\n"
            + "  \"highlights\": [\"亮点\"],\n"
            + "  \"test_suggestions\": [\"测试建议\"]\n"
            + "}\n```\n\n"
            + "如果原始内容中没有明确的问题，则 issues 为空数组，has_critical 为 false。\n"
            + "请根据原始回复内容提取信息，不要编造问题。\n\n"
            + "原始回复内容：\n%s";

    private LlmResponse callLlmWithRetry(PromptBuilder.PromptContent prompt) throws LlmApiException {
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

                    // 如果首次返回非 JSON，尝试用重试提示词让模型重新格式化
                    if (response.isRawText() && response.getRawText() != null && !response.getRawText().isBlank()) {
                        log.info("首次响应非 JSON，发起格式化重试...");
                        try {
                            String retryUserPrompt = String.format(JSON_RETRY_USER_TEMPLATE, response.getRawText());
                            String retryResponse = provider.call(JSON_RETRY_SYSTEM_PROMPT, retryUserPrompt);
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
                    int delay = e.isRateLimitError() ? (int) RETRY_DELAY_MS : 5_000;
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
     * 表示LLM的响应，优先解析为结构化JSON，回退到原始文本。
     */
    static class LlmResponse {
        private final List<ReviewIssue> issues;
        private final String rawText;
        private final Boolean hasCritical;

        private LlmResponse(List<ReviewIssue> issues, String rawText, Boolean hasCritical) {
            this.issues = issues;
            this.rawText = rawText;
            this.hasCritical = hasCritical;
        }

        boolean isRawText() {
            return rawText != null;
        }

        List<ReviewIssue> getIssues() {
            return issues;
        }

        String getRawText() {
            return rawText;
        }

        Boolean getHasCritical() {
            return hasCritical;
        }

        static LlmResponse fromContent(String content) {

            if (content == null || content.isBlank()) {
                return new LlmResponse(List.of(), null, false);
            }

            // 预处理：去除 markdown 代码块包裹和常见的思考标签
            String cleaned = stripWrappers(content);

            // 1. 优先尝试 JSON 对象格式（包含 has_critical 明确标志）
            try {
                String jsonObj = extractJsonObject(cleaned);
                if (jsonObj != null) {
                    Map<String, Object> parsed = MAPPER.readValue(jsonObj, new TypeReference<Map<String, Object>>() {});
                    boolean critical = false;
                    Object criticalFlag = parsed.get("has_critical");
                    if (criticalFlag instanceof Boolean) {
                        critical = (Boolean) criticalFlag;
                    }
                    List<ReviewIssue> issues = List.of();
                    Object issuesObj = parsed.get("issues");
                    if (issuesObj != null) {
                        String issuesJson = MAPPER.writeValueAsString(issuesObj);
                        issues = MAPPER.readValue(issuesJson, new TypeReference<List<ReviewIssue>>() {});
                    }
                    return new LlmResponse(issues, null, critical);
                }
            } catch (Exception e) {
                log.debug("LLM 输出非 JSON 对象格式，尝试 JSON 数组");
            }

            // 2. 尝试 JSON 数组格式（向后兼容旧 prompt 输出）
            try {
                String json = extractJsonArray(cleaned);
                if (json != null) {
                    List<ReviewIssue> issues = MAPPER.readValue(json, new TypeReference<List<ReviewIssue>>() {});
                    return new LlmResponse(issues, null, null);
                }
            } catch (Exception e) {
                log.debug("LLM 输出非 JSON 格式，作为原始文本处理");
            }

            // 3. 原始文本 fallback（不可靠，仅作为最后手段）
            log.warn("LLM 未输出有效 JSON，降级为原始文本模式。commit 阻断判定可能不准确。");
            log.warn("LLM 原始响应（前200字符）：{}", content.substring(0, Math.min(200, content.length())));
            return new LlmResponse(List.of(), content, null);
        }

        /**
         * 去除 LLM 输出中常见的包裹标记：
         * - markdown 代码块（```json ... ```）
         * - XML 风格思考标签（<thinking>...</thinking> 等）
         */
        private static String stripWrappers(String content) {
            String s = content;
            // 去除 markdown 代码块
            s = s.replaceAll("(?s)```(?:json)?\\s*\\n?", "");
            s = s.replaceAll("(?s)\\n?\\s*```", "");
            // 去除 <thinking> / <think /> 标签
            s = s.replaceAll("(?s)<thinking>.*?</thinking>", "");
            s = s.replaceAll("(?s)<think\\s*/>", "");
            return s.trim();
        }

        private static String extractJsonObject(String content) {
            if (content == null) return null;
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return content.substring(start, end + 1);
            }
            return null;
        }

        private static String extractJsonArray(String content) {
            if (content == null) return null;
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start >= 0 && end > start) {
                return content.substring(start, end + 1);
            }
            return null;
        }
    }
}
