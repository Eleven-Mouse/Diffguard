package com.diffguard.llm.provider;

import com.diffguard.exception.LlmApiException;
import com.diffguard.util.JacksonMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 检测代理 API 返回的伪装错误响应。
 * 某些第三方代理在 HTTP 200 响应体中包裹错误信息，
 * 例如 {"success": false, "code": 500, "msg": "..."} 或 {"error": {"message": "..."}}。
 */
public final class ProxyResponseDetector {

    private static final Logger log = LoggerFactory.getLogger(ProxyResponseDetector.class);

    private ProxyResponseDetector() {}

    /**
     * 检测 LLM 响应文本中是否包含代理错误特征。
     * 如果检测到代理错误，抛出 LlmApiException。
     *
     * @param responseText LLM 返回的原始文本
     * @throws LlmApiException 检测到代理错误时抛出
     */
    @SuppressWarnings("unchecked")
    public static void validate(String responseText) throws LlmApiException {
        if (responseText == null || responseText.isBlank()) {
            return;
        }

        String trimmed = responseText.trim();
        if (!trimmed.startsWith("{")) {
            return;
        }

        try {
            Map<String, Object> parsed = JacksonMapper.MAPPER.readValue(
                    trimmed, new TypeReference<Map<String, Object>>() {});

            // 模式 1：{"success": false, "code": 500, "msg": "..."}
            Object successFlag = parsed.get("success");
            if (successFlag instanceof Boolean && !(Boolean) successFlag) {
                String msg = String.valueOf(parsed.getOrDefault("msg",
                        parsed.getOrDefault("message", "未知错误")));
                String code = String.valueOf(parsed.getOrDefault("code", "unknown"));
                log.error("API 返回业务错误（HTTP 200 包装）：code={}, msg={}", code, msg);
                throw new LlmApiException(500,
                        "API 业务错误（" + code + "）：" + truncate(msg, 200));
            }

            // 模式 2：{"error": {"message": "..."}} 且不含正常响应字段
            if (parsed.containsKey("error") && !parsed.containsKey("choices") && !parsed.containsKey("content")) {
                Object errorObj = parsed.get("error");
                String errorMsg = errorObj instanceof Map
                        ? String.valueOf(((Map<String, Object>) errorObj).getOrDefault("message", errorObj))
                        : String.valueOf(errorObj);
                log.error("API 响应包含 error 字段且无正常内容：{}", truncate(responseText, 500));
                throw new LlmApiException(500, "API 错误：" + truncate(errorMsg, 200));
            }

        } catch (LlmApiException e) {
            throw e;
        } catch (Exception e) {
            // JSON 解析失败，不是代理错误，忽略
            log.debug("响应文本非 JSON 格式，跳过代理错误检测");
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
