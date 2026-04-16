package com.diffguard.llm;

import com.diffguard.config.ReviewConfig;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
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

    private final ReviewConfig config;
    private final HttpClient httpClient;
    private int totalTokensUsed = 0;

    public LlmClient(ReviewConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getLlm().getTimeoutSeconds()))
                .build();
    }

    /**
     * Review a list of prompts and aggregate results.
     */
    public ReviewResult review(List<PromptBuilder.PromptContent> prompts) {
        ReviewResult result = new ReviewResult();
        long startTime = System.currentTimeMillis();

        for (PromptBuilder.PromptContent prompt : prompts) {
            try {
                List<ReviewIssue> issues = callLlm(prompt);
                for (ReviewIssue issue : issues) {
                    result.addIssue(issue);
                }
                result.setTotalFilesReviewed(result.getTotalFilesReviewed() + 1);
            } catch (Exception e) {
                System.err.println("LLM call failed: " + e.getMessage());
            }
        }

        result.setTotalTokensUsed(totalTokensUsed);
        result.setReviewDurationMs(System.currentTimeMillis() - startTime);
        return result;
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
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
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
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(config.getLlm().getTimeoutSeconds()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("OpenAI API error (" + response.statusCode() + "): " + response.body());
        }

        return extractContentFromOpenAIResponse(response.body());
    }

    private String extractContentFromClaudeResponse(String responseBody) throws IOException {
        Map<String, Object> response = MAPPER.readValue(responseBody, new TypeReference<>() {});

        // Track token usage
        if (response.containsKey("usage")) {
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            totalTokensUsed += ((Number) usage.getOrDefault("input_tokens", 0)).intValue();
            totalTokensUsed += ((Number) usage.getOrDefault("output_tokens", 0)).intValue();
        }

        // Extract text content
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content != null && !content.isEmpty()) {
            return (String) content.get(0).get("text");
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
            return (String) message.get("content");
        }
        return "";
    }

    private List<ReviewIssue> parseIssues(String content) {
        try {
            // Try to extract JSON array from the response
            String json = extractJsonArray(content);
            if (json != null) {
                return MAPPER.readValue(json, new TypeReference<List<ReviewIssue>>() {});
            }
        } catch (Exception e) {
            System.err.println("Failed to parse LLM response as structured issues: " + e.getMessage());
        }

        // Fallback: create a single INFO issue with the raw content
        List<ReviewIssue> issues = new ArrayList<>();
        ReviewIssue issue = new ReviewIssue();
        issue.setType("Review Summary");
        issue.setMessage(content);
        issues.add(issue);
        return issues;
    }

    private String extractJsonArray(String content) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }
}
