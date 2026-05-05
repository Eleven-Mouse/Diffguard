package com.diffguard.infrastructure.llm.provider;

import com.diffguard.exception.LlmApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM Provider 共享工具方法。
 * 集中异常翻译、状态码提取等跨 Provider 复用的逻辑。
 */
public final class ProviderUtils {

    private static final Logger log = LoggerFactory.getLogger(ProviderUtils.class);

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

        if (isQuotaError(translated)) {
            log.error("LLM quota/billing 错误（statusCode={}）：{}", statusCode, message);
            log.error("→ 请检查：1) 代理服务余额  2) API Key 是否匹配当前代理  3) 模型是否在当前套餐内");
        }

        return translated;
    }

    /** quota/billing 相关错误关键字，用于检测不可重试的账户错误 */
    private static final String[] QUOTA_KEYWORDS = {
            "insufficient_user_quota", "insufficient_quota",
            "billing", "plan_limit", "quota_exceeded"
    };

    /**
     * 从异常消息中提取 HTTP 状态码。
     * 匹配常见状态码模式（429, 402, 500, 502, 503, 400, 529）。
     *
     * @param e 异常
     * @return 提取到的状态码，未匹配则返回 -1
     */
    public static int extractStatusCode(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return -1;
        if (msg.contains("429")) return 429;
        if (msg.contains("402")) return 402;
        if (msg.contains("500")) return 500;
        if (msg.contains("502")) return 502;
        if (msg.contains("503")) return 503;
        if (msg.contains("504")) return 504;
        if (msg.contains("529")) return 529; // Claude 特有的过载错误
        if (msg.contains("400")) return 400;
        return -1;
    }

    /**
     * 检测异常是否为 quota/billing 错误。
     * 这类错误不可重试，应立即终止后续请求。
     */
    public static boolean isQuotaError(Exception e) {
        if (e instanceof LlmApiException lae && lae.isQuotaError()) return true;
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                for (String keyword : QUOTA_KEYWORDS) {
                    if (lower.contains(keyword)) return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
