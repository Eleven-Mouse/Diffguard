package com.diffguard.llm.provider;

import com.diffguard.config.ReviewConfig;
import com.diffguard.exception.LlmApiException;
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
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude API 供应商实现。
 */
public class ClaudeProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ReviewConfig.LlmConfig config;
    private final HttpClient httpClient;
    private final TokenTracker tokenTracker;

    public ClaudeProvider(ReviewConfig.LlmConfig config, HttpClient httpClient, TokenTracker tokenTracker) {
        this.config = config;
        this.httpClient = httpClient;
        this.tokenTracker = tokenTracker;
    }

    @Override
    public String call(String systemPrompt, String userPrompt) throws LlmApiException, IOException, InterruptedException {
        String apiKey = config.resolveApiKey();
        String baseUrl = config.resolveBaseUrl();

        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "max_tokens", config.getMaxTokens(),
                "temperature", config.getTemperature(),
                "system", systemPrompt,
                "messages", List.of(
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        String jsonBody = MAPPER.writeValueAsString(body);
        log.debug("Claude API 请求：model={}, base_url={}", config.getModel(), baseUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String errorBody = response.body();
            log.error("Claude API 错误：status={}, body={}", response.statusCode(), truncate(errorBody, 500));
            log.debug("Claude API 错误完整响应：{}", errorBody);
            throw new LlmApiException(response.statusCode(), "Claude API 错误（" + response.statusCode() + "）：" + truncate(errorBody, 200));
        }

        return extractContent(response.body());
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String responseBody) throws IOException, LlmApiException {
        Map<String, Object> response = MAPPER.readValue(responseBody, new TypeReference<>() {});

        // 检测代理返回的伪装为 HTTP 200 的错误响应
        Object successFlag = response.get("success");
        if (successFlag instanceof Boolean && !(Boolean) successFlag) {
            String msg = String.valueOf(response.getOrDefault("msg", response.getOrDefault("message", "未知错误")));
            String code = String.valueOf(response.getOrDefault("code", "unknown"));
            log.error("API 返回业务错误（HTTP 200 包装）：code={}, msg={}, body={}", code, msg, truncate(responseBody, 500));
            throw new LlmApiException(500, "API 业务错误（" + code + "）：" + truncate(msg, 200));
        }
        if (response.containsKey("error") && !response.containsKey("content")) {
            Object errorObj = response.get("error");
            String errorMsg = errorObj instanceof Map ? String.valueOf(((Map<String, Object>) errorObj).getOrDefault("message", errorObj)) : String.valueOf(errorObj);
            log.error("API 响应包含 error 字段且无 content：{}", truncate(responseBody, 500));
            throw new LlmApiException(500, "API 错误：" + truncate(errorMsg, 200));
        }

        if (response.containsKey("usage") && tokenTracker != null) {
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            int tokens = ((Number) usage.getOrDefault("input_tokens", 0)).intValue()
                    + ((Number) usage.getOrDefault("output_tokens", 0)).intValue();
            tokenTracker.addTokens(tokens);
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

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }
}
