package com.diffguard.llm;

import com.diffguard.config.ReviewConfig;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.output.ProgressDisplay;
import com.diffguard.prompt.PromptBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 15_000;

    private final ReviewConfig config;
    private final HttpClient httpClient;
    private int totalTokensUsed = 0;

    public LlmClient(ReviewConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getLlm().getTimeoutSeconds()))
                .build();
    }

    public ReviewResult review(List<PromptBuilder.PromptContent> prompts) {
        ReviewResult result = new ReviewResult();
        long startTime = System.currentTimeMillis();

        ProgressDisplay.printReviewStart(prompts.size());

        for (int i = 0; i < prompts.size(); i++) {
            if (prompts.size() > 1) {
                ProgressDisplay.printBatchProgress(i + 1, prompts.size());
            }

            try {
                List<ReviewIssue> issues = callLlmWithRetry(prompts.get(i));
                for (ReviewIssue issue : issues) {
                    result.addIssue(issue);
                }
                result.setTotalFilesReviewed(result.getTotalFilesReviewed() + 1);
            } catch (Exception e) {
                System.err.println("  " + e.getMessage());
            }
        }

        result.setTotalTokensUsed(totalTokensUsed);
        result.setReviewDurationMs(System.currentTimeMillis() - startTime);

        ProgressDisplay.printReviewComplete(result.getIssues().size());
        return result;
    }

    private List<ReviewIssue> callLlmWithRetry(PromptBuilder.PromptContent prompt)
            throws IOException, InterruptedException {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                // Start waiting animation
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
        throw new IOException("All retries exhausted", lastException);
    }

    private boolean isRateLimitError(String message) {
        return message != null && (message.contains("429") || message.contains("rate") || message.contains("请求数限制"));
    }

    private List<ReviewIssue> callLlm(PromptBuilder.PromptContent prompt) throws IOException, InterruptedException {
        String provider = config.getLlm().getProvider().toLowerCase();
        String responseBody = switch (provider) {
            case "claude" -> callClaude(prompt);
            case "openai" -> callOpenAI(prompt);
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };
        return parseIssues(responseBody);
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
            throw new IOException("Claude API error (" + response.statusCode() + "): " + response.body());
        }

        return extractContentFromClaudeResponse(response.body());
    }

    private String callOpenAI(PromptBuilder.PromptContent prompt) throws IOException, InterruptedException {
        String apiKey = config.getLlm().resolveApiKey();
        String baseUrl = config.getLlm().resolveBaseUrl();

        // DEBUG: show what we're sending
        String keyPreview = apiKey != null
                ? apiKey.substring(0, Math.min(8, apiKey.length())) + "..."
                : "NULL";
        System.out.println("  [DEBUG] URL: " + baseUrl + "/chat/completions");
        System.out.println("  [DEBUG] API key: " + keyPreview);
        System.out.println("  [DEBUG] Model: " + config.getLlm().getModel());

        Map<String, Object> body = Map.of(
                "model", config.getLlm().getModel(),
                "max_tokens", config.getLlm().getMaxTokens(),
                "temperature", config.getLlm().getTemperature(),
                "messages", List.of(
                        Map.of("role", "system", "content", prompt.getSystemPrompt()),
                        Map.of("role", "user", "content", prompt.getUserPrompt())
                )
        );

        String jsonBody = MAPPER.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(config.getLlm().getTimeoutSeconds()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("OpenAI API error (" + response.statusCode() + "): " + response.body());
        }

        // DEBUG: print raw response to diagnose parsing issues
        System.out.println("  [DEBUG] API response (first 2000 chars):");
        String rawBody = response.body();
        System.out.println("  " + rawBody.substring(0, Math.min(rawBody.length(), 2000)));
        System.out.println();

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
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");
            // If content is empty, fall back to reasoning_content (extended thinking format)
            if (content == null || content.isBlank()) {
                Object reasoning = message.get("reasoning_content");
                if (reasoning instanceof String r && !r.isBlank()) {
                    return r;
                }
            }
            return content != null ? content : "";
        }
        return "";
    }

    private List<ReviewIssue> parseIssues(String content) {
        if (content == null || content.isBlank()) return List.of();

        try {
            String json = extractJsonArray(content);
            if (json != null) {
                return MAPPER.readValue(json, new TypeReference<List<ReviewIssue>>() {});
            }
        } catch (Exception e) {
            System.err.println("Failed to parse LLM response: " + e.getMessage());
        }

        List<ReviewIssue> issues = new ArrayList<>();
        ReviewIssue issue = new ReviewIssue();
        issue.setType("Review Summary");
        issue.setMessage(content);
        issues.add(issue);
        return issues;
    }

    private String extractJsonArray(String content) {
        if (content == null) return null;
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }
}
