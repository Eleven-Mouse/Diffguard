package com.diffguard.llm;

import com.diffguard.config.ReviewConfig;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 15_000;

    /** 需要禁用扩展思考的 OpenAI 模型 */
    private static final Set<String> THINKING_MODELS = Set.of("o1", "o1-mini", "o3", "o3-mini", "o3-pro");

    private final ReviewConfig config;
    private final HttpClient httpClient;
    private int totalTokensUsed = 0;

    public LlmClient(ReviewConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(config.getLlm().getTimeoutSeconds()))
                .build();
    }

    public ReviewResult review(List<PromptBuilder.PromptContent> prompts) throws Exception {
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

    private LlmResponse callLlmWithRetry(PromptBuilder.PromptContent prompt)
            throws IOException, InterruptedException {
        Exception lastException = null;

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
                    return callLlm(prompt);
                } finally {
                    animator.interrupt();
                    animator.join(300);
                    ProgressDisplay.clearWaiting();
                }
            } catch (IOException e) {
                lastException = e;
                if (isRateLimitError(e.getMessage()) && attempt < MAX_RETRIES) {
                    ProgressDisplay.printRateLimitRetry(attempt, MAX_RETRIES, (int) (RETRY_DELAY_MS / 1000));
                    Thread.sleep(RETRY_DELAY_MS);
                } else {
                    throw e;
                }
            }
        }
        throw new IOException("所有重试已耗尽", lastException);
    }

    private boolean isRateLimitError(String message) {
        return message != null && (message.contains("429") || message.contains("rate") || message.contains("请求数限制"));
    }

    private LlmResponse callLlm(PromptBuilder.PromptContent prompt) throws IOException, InterruptedException {
        String provider = config.getLlm().getProvider().toLowerCase();
        String responseBody = switch (provider) {
            case "claude" -> callClaude(prompt);
            case "openai" -> callOpenAI(prompt);
            default -> throw new IllegalArgumentException("不支持的提供商：" + provider);
        };
        return LlmResponse.fromContent(responseBody);
    }

    private String callClaude(PromptBuilder.PromptContent prompt) throws IOException, InterruptedException {
        String apiKey = config.getLlm().resolveApiKey();
        String baseUrl = config.getLlm().resolveBaseUrl();

        Map<String, Object> body = Map.of(
                "model", config.getLlm().getModel(),
                "max_tokens", config.getLlm().getMaxTokens(),
                "temperature", config.getLlm().getTemperature(),
                "system", prompt.getSystemPrompt(),
                "messages", List.of(
                        Map.of("role", "user", "content", prompt.getUserPrompt())
                )
        );

        String jsonBody = MAPPER.writeValueAsString(body);
        log.debug("Claude API 请求：model={}, base_url={}", config.getLlm().getModel(), baseUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(config.getLlm().getTimeoutSeconds()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Claude API 错误：status={}, body={}", response.statusCode(), response.body());
            throw new IOException("Claude API 错误（" + response.statusCode() + "）：" + response.body());
        }

        return extractContentFromClaudeResponse(response.body());
    }

    private String callOpenAI(PromptBuilder.PromptContent prompt) throws IOException, InterruptedException {
        String apiKey = config.getLlm().resolveApiKey();
        String baseUrl = config.getLlm().resolveBaseUrl();
        String maskedKey = apiKey.length() > 8
                ? apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4)
                : "****";
        log.debug("OpenAI 请求：url={}/chat/completions, key={}, model={}", baseUrl, maskedKey, config.getLlm().getModel());

        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getLlm().getModel());
        body.put("max_tokens", config.getLlm().getMaxTokens());
        body.put("temperature", config.getLlm().getTemperature());
        body.put("messages", List.of(
                Map.of("role", "system", "content", prompt.getSystemPrompt()),
                Map.of("role", "user", "content", prompt.getUserPrompt())
        ));

        // 仅对扩展思考模型禁用思考功能，避免标准模型返回 400 错误
        String model = config.getLlm().getModel().toLowerCase();
        if (THINKING_MODELS.stream().anyMatch(model::startsWith)) {
            body.put("thinking", Map.of("type", "disabled"));
        }

        String jsonBody = MAPPER.writeValueAsString(body);
        log.debug("OpenAI API 请求：model={}, base_url={}", config.getLlm().getModel(), baseUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(config.getLlm().getTimeoutSeconds()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("OpenAI API 错误：status={}, body={}", response.statusCode(), response.body());
            throw new IOException("OpenAI API 错误（" + response.statusCode() + "）：" + response.body());
        }

        return extractContentFromOpenAIResponse(response.body());
    }

    @SuppressWarnings("unchecked")
    private String extractContentFromClaudeResponse(String responseBody) throws IOException {
        Map<String, Object> response = MAPPER.readValue(responseBody, new TypeReference<>() {});

        if (response.containsKey("usage")) {
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            totalTokensUsed += ((Number) usage.getOrDefault("input_tokens", 0)).intValue();
            totalTokensUsed += ((Number) usage.getOrDefault("output_tokens", 0)).intValue();
        }

        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content != null && !content.isEmpty()) {
            String thinkingFallback = null;
            for (Map<String, Object> block : content) {
                if ("text".equals(block.get("type"))) {
                    String text = (String) block.get("text");
                    return text != null ? text : "";
                }
                if ("thinking".equals(block.get("type")) && thinkingFallback == null) {
                    thinkingFallback = (String) block.get("thinking");
                }
            }
            if (thinkingFallback != null) return thinkingFallback;
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private String extractContentFromOpenAIResponse(String responseBody) throws IOException {
        Map<String, Object> response = MAPPER.readValue(responseBody, new TypeReference<>() {});

        if (response.containsKey("usage")) {
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            totalTokensUsed += ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue();
            totalTokensUsed += ((Number) usage.getOrDefault("completion_tokens", 0)).intValue();
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> firstChoice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
            if (message == null) {
                log.warn("API 响应中 choices[0].message 为 null，响应内容：{}", responseBody);
                return "";
            }
            String content = (String) message.get("content");
            if (content == null || content.isBlank()) {
                Object reasoning = message.get("reasoning_content");
                if (reasoning instanceof String r && !r.isBlank()) {
                    return r;
                }
            }
            return content != null ? content : "";
        }
        log.warn("API 响应中无有效 choices，响应内容：{}", responseBody);
        return "";
    }

    /**
     * 表示LLM的响应，可以是结构化的JSON问题列表，也可以是原始文本报告。
     */
    static class LlmResponse {
        private final List<ReviewIssue> issues;
        private final String rawText;

        private LlmResponse(List<ReviewIssue> issues, String rawText) {
            this.issues = issues;
            this.rawText = rawText;
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

        static LlmResponse fromContent(String content) {
            if (content == null || content.isBlank()) {
                return new LlmResponse(List.of(), null);
            }

            // 先尝试JSON解析（向后兼容）
            try {
                String json = extractJsonArrayStatic(content);
                if (json != null) {
                    List<ReviewIssue> issues = MAPPER.readValue(json, new TypeReference<List<ReviewIssue>>() {});
                    return new LlmResponse(issues, null);
                }
            } catch (Exception e) {
                log.debug("LLM 输出非 JSON 格式，作为原始文本处理");
            }

            // 原始文本报告
            return new LlmResponse(List.of(), content);
        }

        private static String extractJsonArrayStatic(String content) {
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
