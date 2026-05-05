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

import static com.diffguard.infrastructure.llm.provider.LlmConstants.*;

/**
 * 直接 HTTP 调用 OpenAI API（兼容代理）的 LLM Provider。
 * <p>
 * 支持代理兼容逻辑：
 * <ul>
 *   <li>response_format 降级：首次带 response_format 调用，若代理返回 400 则降级重试</li>
 *   <li>代理错误检测：检测 HTTP 200 响应体中伪装的错误信息</li>
 *   <li>模型特定参数：GPT-5 系列不传 temperature，o1/o3 禁用 thinking</li>
 * </ul>
 */
public class OpenAiHttpProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiHttpProvider.class);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final int timeoutSeconds;
    private final boolean skipTemperature;
    private final boolean isClaudeViaProxy;
    private final TokenTracker tokenTracker;
    private final HttpClient httpClient;

    public OpenAiHttpProvider(ReviewConfig.LlmConfig config, TokenTracker tokenTracker) {
        this.apiKey = config.resolveApiKey();
        this.baseUrl = config.resolveBaseUrl();
        this.model = config.getModel();
        this.maxTokens = config.getMaxTokens();
        this.temperature = config.getTemperature();
        this.timeoutSeconds = config.getTimeoutSeconds();
        this.skipTemperature = NO_TEMPERATURE_MODELS.contains(model.toLowerCase());
        this.isClaudeViaProxy = model.toLowerCase().contains("claude");
        this.tokenTracker = tokenTracker;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        log.info("LLM Provider 初始化：provider=openai, model={}, baseUrl={}, keyPrefix={}...",
                model, baseUrl, apiKey.length() >= 4 ? apiKey.substring(0, 4) + "***" : "N/A");
    }

    @Override
    public String call(String systemPrompt, String userPrompt)
            throws LlmApiException, IOException, InterruptedException {
        try {
            String response = doCall(systemPrompt, userPrompt, !isClaudeViaProxy);
            ProxyResponseDetector.validate(response);
            return response;
        } catch (Exception e) {
            if (isProxyResponseFormatError(e)) {
                log.info("代理可能不支持 response_format 参数，降级为普通请求重试（追加 JSON 强化指令）");
                try {
                    String response = doCall(systemPrompt, userPrompt + JSON_ENFORCE_SUFFIX, false);
                    ProxyResponseDetector.validate(response);
                    return response;
                } catch (Exception fallbackEx) {
                    throw ProviderUtils.translateException(fallbackEx, "LLM 调用失败");
                }
            }
            throw ProviderUtils.translateException(e, "LLM 调用失败");
        }
    }

    private String doCall(String systemPrompt, String userPrompt, boolean useJsonFormat)
            throws IOException, InterruptedException, LlmApiException {
        ObjectNode body = JacksonMapper.MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (!skipTemperature) {
            body.put("temperature", temperature);
        }
        if (useJsonFormat) {
            ObjectNode format = JacksonMapper.MAPPER.createObjectNode();
            format.put("type", "json_object");
            body.set("response_format", format);
        }

        ArrayNode messages = body.putArray("messages");
        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        String jsonBody = JacksonMapper.MAPPER.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (httpResponse.statusCode() != 200) {
            throw new LlmApiException(httpResponse.statusCode(),
                    "OpenAI API error: " + truncate(httpResponse.body(), 500));
        }

        JsonNode root = JacksonMapper.MAPPER.readTree(httpResponse.body());

        // Track token usage
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode() && tokenTracker != null) {
            int tokens = usage.path("prompt_tokens").asInt(0) + usage.path("completion_tokens").asInt(0);
            if (tokens > 0) {
                tokenTracker.addTokens(tokens);
            }
        }

        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isEmpty()) {
            log.warn("OpenAI 返回空 content: model={}, finishReason={}",
                    model, root.path("choices").path(0).path("finish_reason").asText(""));
            return "";
        }

        return content;
    }

    private boolean isProxyResponseFormatError(Exception e) {
        if (e.getMessage() == null) return false;
        String msg = e.getMessage().toLowerCase();
        return msg.contains("400") || msg.contains("bad request")
                || msg.contains("response_format") || msg.contains("invalid_request");
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
