package com.diffguard.llm.provider;

import com.diffguard.config.ReviewConfig;
import com.diffguard.exception.LlmApiException;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 基于 LangChain4j 的 Claude 适配器，实现 {@link LlmProvider} 接口。
 * <p>
 * 支持代理兼容逻辑：
 * <ul>
 *   <li>代理错误检测：检测 HTTP 200 响应体中伪装的错误信息</li>
 * </ul>
 */
public class LangChain4jClaudeAdapter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jClaudeAdapter.class);

    private final ChatModel model;
    private final TokenTracker tokenTracker;

    public LangChain4jClaudeAdapter(ReviewConfig.LlmConfig config, TokenTracker tokenTracker) {
        this.tokenTracker = tokenTracker;
        String apiKey = config.resolveApiKey();
        String baseUrl = config.resolveBaseUrl();

        this.model = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(config.getModel())
                .maxTokens(config.getMaxTokens())
                .temperature(config.getTemperature())
                .timeout(java.time.Duration.ofSeconds(config.getTimeoutSeconds()))
                .maxRetries(0)
                .build();

        log.info("LLM 适配器初始化：provider=claude, model={}, baseUrl={}, keyPrefix={}...",
                config.getModel(), baseUrl,
                apiKey != null && apiKey.length() >= 4 ? apiKey.substring(0, 4) + "***" : "N/A");
    }

    /**
     * 包内可见构造方法，用于测试注入 mock ChatModel。
     */
    LangChain4jClaudeAdapter(ChatModel model, TokenTracker tokenTracker) {
        this.model = model;
        this.tokenTracker = tokenTracker;
    }

    /**
     * 返回用于 AiServices 的 ChatModel 实例。
     */
    public ChatModel getChatModel() {
        return model;
    }

    @Override
    public String call(String systemPrompt, String userPrompt)
            throws LlmApiException, IOException, InterruptedException {
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt))
                    .build();
            ChatResponse response = model.chat(request);
            return extractAndValidate(response);
        } catch (Exception e) {
            throw ProviderUtils.translateException(e, "Claude API 调用失败");
        }
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
}
