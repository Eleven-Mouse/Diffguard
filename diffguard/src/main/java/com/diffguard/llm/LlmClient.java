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
import java.util.List;
import java.util.Map;

public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 15_000;

    private final LlmProvider provider;
    private final HttpClient httpClient;
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

        StringBuilder rawReportBuilder = new StringBuilder();

        for (int i = 0; i < prompts.size(); i++) {
            if (prompts.size() > 1) {
                ProgressDisplay.printBatchProgress(i + 1, prompts.size());
            }

            LlmResponse response = callLlmWithRetry(prompts.get(i));
            if (response.isRawText()) {
                if (rawReportBuilder.length() > 0) {
                    rawReportBuilder.append("\n\n");
                }
                rawReportBuilder.append(response.getRawText());
            } else {
                for (ReviewIssue issue : response.getIssues()) {
                    result.addIssue(issue);
                }
                // 传播 hasCritical 标志：JSON 显式标记 或 存在 CRITICAL 级别 issue
                if (Boolean.TRUE.equals(response.getHasCritical())
                        || response.getIssues().stream().anyMatch(issue -> issue.getSeverity().shouldBlockCommit())) {
                    result.setHasCriticalFlag(true);
                }
            }
            result.setTotalFilesReviewed(result.getTotalFilesReviewed() + 1);
        }

        if (rawReportBuilder.length() > 0) {
            result.setRawReport(rawReportBuilder.toString());
        }

        result.setTotalTokensUsed(totalTokensUsed);
        result.setReviewDurationMs(System.currentTimeMillis() - startTime);

        int totalIssues = result.isRawReport() ? 0 : result.getIssues().size();
        ProgressDisplay.printReviewComplete(totalIssues);
        return result;
    }

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
                    return LlmResponse.fromContent(responseBody);
                } finally {
                    animator.interrupt();
                    animator.join(300);
                    ProgressDisplay.clearWaiting();
                }
            } catch (LlmApiException e) {
                lastException = e;
                if (e.isRateLimitError() && attempt < MAX_RETRIES) {
                    ProgressDisplay.printRateLimitRetry(attempt, MAX_RETRIES, (int) (RETRY_DELAY_MS / 1000));
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
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

            // 1. 优先尝试 JSON 对象格式（包含 has_critical 明确标志）
            try {
                String jsonObj = extractJsonObject(content);
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
                String json = extractJsonArray(content);
                if (json != null) {
                    List<ReviewIssue> issues = MAPPER.readValue(json, new TypeReference<List<ReviewIssue>>() {});
                    return new LlmResponse(issues, null, null);
                }
            } catch (Exception e) {
                log.debug("LLM 输出非 JSON 格式，作为原始文本处理");
            }

            // 3. 原始文本 fallback（不可靠，仅作为最后手段）
            log.warn("LLM 未输出有效 JSON，降级为原始文本模式。commit 阻断判定可能不准确。");
            return new LlmResponse(List.of(), content, null);
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
