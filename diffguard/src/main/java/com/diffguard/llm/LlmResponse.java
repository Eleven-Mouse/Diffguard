package com.diffguard.llm;

import com.diffguard.model.ReviewIssue;
import com.diffguard.util.JacksonMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 表示 LLM 的响应，优先解析为结构化 JSON，回退到原始文本。
 * <p>
 * 从 {@link LlmClient} 中提取的独立 JSON 响应解析逻辑，
 * 职责单一：接收原始 LLM 文本输出，尝试解析为结构化 ReviewIssue 列表。
 */
public class LlmResponse {

    private static final Logger log = LoggerFactory.getLogger(LlmResponse.class);

    private final List<ReviewIssue> issues;
    private final String rawText;
    private final Boolean hasCritical;

    LlmResponse(List<ReviewIssue> issues, String rawText, Boolean hasCritical) {
        this.issues = issues;
        this.rawText = rawText;
        this.hasCritical = hasCritical;
    }

    public boolean isRawText() {
        return rawText != null;
    }

    public List<ReviewIssue> getIssues() {
        return issues;
    }

    public String getRawText() {
        return rawText;
    }

    public Boolean getHasCritical() {
        return hasCritical;
    }

    /**
     * 从 LLM 原始输出文本构造 LlmResponse。
     * 优先尝试 JSON 对象解析，其次 JSON 数组，最后作为原始文本。
     */
    public static LlmResponse fromContent(String content) {
        if (content == null || content.isBlank()) {
            return new LlmResponse(List.of(), null, false);
        }

        String cleaned = stripWrappers(content);

        // 1. 优先尝试 JSON 对象格式（包含 has_critical 明确标志）
        try {
            String jsonObj = extractJsonObject(cleaned);
            if (jsonObj != null) {
                Map<String, Object> parsed = JacksonMapper.MAPPER.readValue(jsonObj, new TypeReference<Map<String, Object>>() {});
                boolean critical = false;
                Object criticalFlag = parsed.get("has_critical");
                if (criticalFlag instanceof Boolean) {
                    critical = (Boolean) criticalFlag;
                }
                List<ReviewIssue> issues = List.of();
                Object issuesObj = parsed.get("issues");
                if (issuesObj != null) {
                    String issuesJson = JacksonMapper.MAPPER.writeValueAsString(issuesObj);
                    issues = JacksonMapper.MAPPER.readValue(issuesJson, new TypeReference<List<ReviewIssue>>() {});
                }
                return new LlmResponse(issues, null, critical);
            }
        } catch (Exception e) {
            log.debug("LLM 输出非 JSON 对象格式，尝试 JSON 数组");
        }

        // 2. 尝试 JSON 数组格式（向后兼容旧 prompt 输出）
        try {
            String json = extractJsonArray(cleaned);
            if (json != null) {
                List<ReviewIssue> issues = JacksonMapper.MAPPER.readValue(json, new TypeReference<List<ReviewIssue>>() {});
                return new LlmResponse(issues, null, null);
            }
        } catch (Exception e) {
            log.debug("LLM 输出非 JSON 格式，作为原始文本处理");
        }

        // 3. 原始文本 fallback
        log.warn("LLM 未输出有效 JSON，降级为原始文本模式。commit 阻断判定可能不准确。");
        log.warn("LLM 原始响应（前200字符）：{}", content.substring(0, Math.min(200, content.length())));
        return new LlmResponse(List.of(), content, null);
    }

    /**
     * 去除 LLM 输出中常见的包裹标记：
     * - markdown 代码块（```json ... ```）
     * - XML 风格思考标签（<thinking>...</thinking> 等）
     */
    static String stripWrappers(String content) {
        String s = content;
        s = s.replaceAll("(?s)```(?:json)?\\s*\\n?", "");
        s = s.replaceAll("(?s)\\n?\\s*```", "");
        s = s.replaceAll("(?s)<thinking>.*?</thinking>", "");
        s = s.replaceAll("(?s)<think\\s*/>", "");
        return s.trim();
    }

    static String extractJsonObject(String content) {
        if (content == null) return null;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }

    static String extractJsonArray(String content) {
        if (content == null) return null;
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }
}
