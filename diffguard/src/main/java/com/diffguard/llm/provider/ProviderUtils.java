package com.diffguard.llm.provider;

import com.diffguard.exception.LlmApiException;

/**
 * LLM Provider 共享工具方法。
 * 集中异常翻译、状态码提取等跨 Provider 复用的逻辑。
 */
public final class ProviderUtils {

    private ProviderUtils() {}

    /**
     * 将底层异常翻译为 LlmApiException。
     * 尝试从异常消息中提取 HTTP 状态码，保留原始异常链。
     *
     * @param e 底层异常
     * @param defaultMessage 默认错误消息（当原异常无消息时使用）
     * @return 翻译后的 LlmApiException
     */
    public static LlmApiException translateException(Exception e, String defaultMessage) {
        if (e instanceof LlmApiException lae) return lae;
        Throwable cause = e.getCause();
        if (cause instanceof LlmApiException lae) return lae;

        int statusCode = extractStatusCode(e);
        String message = e.getMessage() != null ? e.getMessage() : defaultMessage;
        LlmApiException translated = new LlmApiException(statusCode, message);
        translated.initCause(e);
        return translated;
    }

    /**
     * 从异常消息中提取 HTTP 状态码。
     * 匹配常见状态码模式（429, 500, 502, 503, 400, 529）。
     *
     * @param e 异常
     * @return 提取到的状态码，未匹配则返回 -1
     */
    public static int extractStatusCode(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return -1;
        if (msg.contains("429")) return 429;
        if (msg.contains("500")) return 500;
        if (msg.contains("502")) return 502;
        if (msg.contains("503")) return 503;
        if (msg.contains("504")) return 504;
        if (msg.contains("529")) return 529; // Claude 特有的过载错误
        if (msg.contains("400")) return 400;
        return -1;
    }
}
