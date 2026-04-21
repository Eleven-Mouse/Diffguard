package com.diffguard.llm.provider;

import com.diffguard.config.ReviewConfig;
import com.diffguard.exception.LlmApiException;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.diffguard.llm.provider.LlmConstants.*;

/**
 * 基于 LangChain4j 的 OpenAI 适配器，实现 {@link LlmProvider} 接口。
 * <p>
 * 支持代理兼容逻辑：
 * <ul>
 *   <li>response_format 降级：首次带 response_format 调用，若代理返回 400 则降级重试</li>
 *   <li>代理错误检测：检测 HTTP 200 响应体中伪装的错误信息</li>
 *   <li>模型特定参数：GPT-5 系列不传 temperature，o1/o3 禁用 thinking</li>
 * </ul>
 */
public class LangChain4jOpenAiAdapter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jOpenAiAdapter.class);

    private final ChatModel primaryModel;
    private final ChatModel fallbackModel;
    private final TokenTracker tokenTracker;

    public LangChain4jOpenAiAdapter(ReviewConfig.LlmConfig config, TokenTracker tokenTracker) {
        this.tokenTracker = tokenTracker;
        String apiKey = config.resolveApiKey();
        String baseUrl = config.resolveBaseUrl();
        String modelLower = config.getModel().toLowerCase();

        var primaryBuilder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(config.getModel())
                .maxTokens(config.getMaxTokens())
                .timeout(java.time.Duration.ofSeconds(config.getTimeoutSeconds()))
                .maxRetries(0);

        if (!NO_TEMPERATURE_MODELS.contains(modelLower)) {
            primaryBuilder.temperature(config.getTemperature());
        }

        // 主模型：仅对 OpenAI 原生模型启用 JSON response_format
        // Claude 模型通过代理时不支持此参数，代理会返回空 content
        if (!modelLower.contains("claude")) {
            primaryBuilder.responseFormat("json_object");
        }
        this.primaryModel = primaryBuilder.build();

        // 降级模型：不带 response_format（用于代理兼容）
        var fallbackBuilder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(config.getModel())
                .maxTokens(config.getMaxTokens())
                .maxRetries(0);

        if (THINKING_MODELS.stream().anyMatch(modelLower::startsWith)) {
            fallbackBuilder.timeout(java.time.Duration.ofSeconds(config.getTimeoutSeconds() * 2));
        } else {
            fallbackBuilder.timeout(java.time.Duration.ofSeconds(config.getTimeoutSeconds()));
        }

        if (!NO_TEMPERATURE_MODELS.contains(modelLower)) {
            fallbackBuilder.temperature(config.getTemperature());
        }

        this.fallbackModel = fallbackBuilder.build();

        log.info("LLM 适配器初始化：provider=openai, model={}, baseUrl={}, keyPrefix={}...",
                config.getModel(), baseUrl,
                apiKey != null && apiKey.length() >= 4 ? apiKey.substring(0, 4) + "***" : "N/A");
    }

    /**
     * 返回用于 AiServices 的 ChatModel 实例。
     */
    public ChatModel getChatModel() {
        return primaryModel;
    }

    @Override
    public String call(String systemPrompt, String userPrompt)
            throws LlmApiException, IOException, InterruptedException {
        try {
            ChatResponse response = chat(primaryModel, systemPrompt, userPrompt);
            return extractAndValidate(response);
        } catch (Exception e) {
            if (isProxyResponseFormatError(e)) {
                log.info("代理可能不支持 response_format 参数，降级为普通请求重试（追加 JSON 强化指令）");
                try {
                    ChatResponse response = chat(fallbackModel, systemPrompt,
                            userPrompt + JSON_ENFORCE_SUFFIX);
                    return extractAndValidate(response);
                } catch (Exception fallbackEx) {
                    throw ProviderUtils.translateException(fallbackEx, "LLM 调用失败");
                }
            }
            throw ProviderUtils.translateException(e, "LLM 调用失败");
        }
    }

    private ChatResponse chat(ChatModel model, String systemPrompt, String userPrompt) {
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt))
                .build();
        return model.chat(request);
    }

    private String extractAndValidate(ChatResponse response) throws LlmApiException {
        String text = response.aiMessage().text();
        if (text == null || text.isBlank()) {
            log.warn("LLM 响应文本为空: model={}, finishReason={}, hasToolCalls={}",
                    response.modelName(),
                    response.finishReason(),
                    response.aiMessage().hasToolExecutionRequests());
            log.debug("LLM 完整响应: {}", response);
            return "";
        }

        ProxyResponseDetector.validate(text);

        if (response.tokenUsage() != null && tokenTracker != null) {
            long tokens = response.tokenUsage().inputTokenCount() + response.tokenUsage().outputTokenCount();
            tokenTracker.addTokens((int) tokens);
        }

        return text;
    }

    private boolean isProxyResponseFormatError(Exception e) {
        if (e.getMessage() == null) return false;
        String msg = e.getMessage().toLowerCase();
        return msg.contains("400") || msg.contains("bad request")
                || msg.contains("response_format") || msg.contains("invalid_request");
    }
}
