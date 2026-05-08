package com.diffguard.infrastructure.llm.provider;

import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.exception.LlmApiException;
import com.diffguard.infrastructure.common.JacksonMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 直接 HTTP 调用 Anthropic Claude API 的 LLM Provider。
 * <p>
 * 使用原生 Anthropic Messages API 格式，不依赖任何框架。
 */
public class ClaudeHttpProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeHttpProvider.class);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final int timeoutSeconds;
    private final TokenTracker tokenTracker;
    private final HttpClient httpClient;

    public ClaudeHttpProvider(ReviewConfig.LlmConfig config, TokenTracker tokenTracker) {
        this.apiKey = config.resolveApiKey();
        this.baseUrl = config.resolveBaseUrl();
        this.model = config.getModel();
        this.maxTokens = config.getMaxTokens();
        this.temperature = config.getTemperature();
        this.timeoutSeconds = config.getTimeoutSeconds();
        this.tokenTracker = tokenTracker;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        log.info("LLM Provider 初始化：provider=claude, model={}, baseUrl={}, keyPrefix={}...",
                model, baseUrl, apiKey.length() >= 4 ? apiKey.substring(0, 4) + "***" : "N/A");
    }

    @Override
    public String call(String systemPrompt, String userPrompt)
            throws LlmApiException, IOException, InterruptedException {
        try {
            String response = doCall(systemPrompt, userPrompt);
            ProxyResponseDetector.validate(response);
            return response;
        } catch (Exception e) {
            throw ProviderUtils.translateException(e, "Claude API 调用失败");
        }
    }

    private String doCall(String systemPrompt, String userPrompt)
            throws IOException, InterruptedException, LlmApiException {
        ObjectNode body = JacksonMapper.MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);

        // Anthropic Messages API: system is a top-level field
        body.put("system", systemPrompt);

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        String jsonBody = JacksonMapper.MAPPER.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (httpResponse.statusCode() != 200) {
            throw new LlmApiException(httpResponse.statusCode(),
                    "Claude API error: " + truncate(httpResponse.body(), 500));
        }

        JsonNode root = JacksonMapper.MAPPER.readTree(httpResponse.body());

        // Track token usage
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode() && tokenTracker != null) {
            int tokens = usage.path("input_tokens").asInt(0) + usage.path("output_tokens").asInt(0);
            if (tokens > 0) {
                tokenTracker.addTokens(tokens);
            }
        }

        // Extract text from content blocks
        JsonNode contentArray = root.path("content");
        if (contentArray.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentArray) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText(""));
                }
            }
            String text = sb.toString();
            if (text.isEmpty()) {
                log.warn("Claude 返回空 content: model={}, stopReason={}",
                        model, root.path("stop_reason").asText(""));
                return "";
            }
            return text;
        }

        return "";
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (Exception e) {
            log.warn("关闭 Claude HttpClient 失败", e);
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
