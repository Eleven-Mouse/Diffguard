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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容 API 供应商实现（同时支持 OpenAI 和兼容的第三方代理）。
 */
public class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 需要禁用扩展思考的模型 */
    private static final Set<String> THINKING_MODELS = Set.of("o1", "o1-mini", "o3", "o3-mini", "o3-pro");

    private final ReviewConfig.LlmConfig config;
    private final HttpClient httpClient;
    private final TokenTracker tokenTracker;

    public OpenAiProvider(ReviewConfig.LlmConfig config, HttpClient httpClient, TokenTracker tokenTracker) {
        this.config = config;
        this.httpClient = httpClient;
        this.tokenTracker = tokenTracker;
    }

    /** 降级模式下追加到用户消息末尾的 JSON 强化指令 */
    private static final String JSON_ENFORCE_SUFFIX = "\n\n【绝对要求】你的回复必须且仅是一个合法的 JSON 对象，以 { 开头，以 } 结尾。禁止输出任何 JSON 之外的内容，包括问候语、解释、能力描述或思考过程。";

    @Override
    public String call(String systemPrompt, String userPrompt) throws LlmApiException, IOException, InterruptedException {
        String apiKey = config.resolveApiKey();
        String baseUrl = config.resolveBaseUrl();
        String maskedKey = apiKey.length() > 8
                ? apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4)
                : "****";
        log.debug("OpenAI 请求：url={}/chat/completions, key={}, model={}", baseUrl, maskedKey, config.getModel());

        // 构建 messages
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        );

        // 首次尝试：带 response_format 强制 JSON 输出
        try {
            return doCall(apiKey, baseUrl, messages, true);
        } catch (LlmApiException e) {
            // 如果代理不支持 response_format（通常返回 400），降级重试
            if (e.getStatusCode() == 400) {
                log.info("代理可能不支持 response_format 参数，降级为普通请求重试（追加 JSON 强化指令）");
                // 降级时在用户消息末尾追加 JSON 强制指令，弥补 response_format 缺失
                List<Map<String, String>> reinforcedMessages = List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt + JSON_ENFORCE_SUFFIX)
                );
                return doCall(apiKey, baseUrl, reinforcedMessages, false);
            }
            throw e;
        }
    }

    private String doCall(String apiKey, String baseUrl, List<Map<String, String>> messages, boolean withResponseFormat)
            throws LlmApiException, IOException, InterruptedException {

        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModel());
        body.put("max_tokens", config.getMaxTokens());
        body.put("temperature", config.getTemperature());
        body.put("messages", messages);

        if (withResponseFormat) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        String modelLower = config.getModel().toLowerCase();
        if (THINKING_MODELS.stream().anyMatch(modelLower::startsWith)) {
            body.put("thinking", Map.of("type", "disabled"));
        }

        String jsonBody = MAPPER.writeValueAsString(body);
        log.debug("OpenAI API 请求：model={}, base_url={}, response_format={}", config.getModel(), baseUrl, withResponseFormat);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String errorBody = response.body();
            log.error("OpenAI API 错误：status={}, body={}", response.statusCode(), truncate(errorBody, 500));
            log.debug("OpenAI API 错误完整响应：{}", errorBody);
            throw new LlmApiException(response.statusCode(), "OpenAI API 错误（" + response.statusCode() + "）：" + truncate(errorBody, 200));
        }

        return extractContent(response.body());
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String responseBody) throws IOException, LlmApiException {
        Map<String, Object> response = MAPPER.readValue(responseBody, new TypeReference<>() {});

        // 检测代理返回的伪装为 HTTP 200 的错误响应
        // 常见格式：{"code":500,"msg":"...","success":false} 或 {"error":{"message":"...","code":...}}
        Object successFlag = response.get("success");
        if (successFlag instanceof Boolean && !(Boolean) successFlag) {
            String msg = String.valueOf(response.getOrDefault("msg", response.getOrDefault("message", "未知错误")));
            String code = String.valueOf(response.getOrDefault("code", "unknown"));
            log.error("API 返回业务错误（HTTP 200 包装）：code={}, msg={}, body={}", code, msg, truncate(responseBody, 500));
            throw new LlmApiException(500, "API 业务错误（" + code + "）：" + truncate(msg, 200));
        }
        if (response.containsKey("error") && !response.containsKey("choices")) {
            Object errorObj = response.get("error");
            String errorMsg = errorObj instanceof Map ? String.valueOf(((Map<String, Object>) errorObj).getOrDefault("message", errorObj)) : String.valueOf(errorObj);
            log.error("API 响应包含 error 字段且无 choices：{}", truncate(responseBody, 500));
            throw new LlmApiException(500, "API 错误：" + truncate(errorMsg, 200));
        }

        if (response.containsKey("usage") && tokenTracker != null) {
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            int tokens = ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue()
                    + ((Number) usage.getOrDefault("completion_tokens", 0)).intValue();
            tokenTracker.addTokens(tokens);
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

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }
}
