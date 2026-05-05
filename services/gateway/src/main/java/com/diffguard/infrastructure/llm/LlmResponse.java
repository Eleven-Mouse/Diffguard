package com.diffguard.infrastructure.llm;

import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.infrastructure.common.JacksonMapper;
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
     * 根据顶层字符判断结构类型：'{' 尝试 JSON 对象，'[' 尝试 JSON 数组，其余依次尝试。
     */
    public static LlmResponse fromContent(String content) {
        if (content == null || content.isBlank()) {
            return new LlmResponse(List.of(), null, false);
        }

        String cleaned = stripWrappers(content);
        char firstSignificant = firstNonWhitespace(cleaned);

        if (firstSignificant == '{') {
            // 顶层是对象，优先 JSON Object
            LlmResponse objResult = tryParseJsonObject(cleaned);
            if (objResult != null) return objResult;
            // 对象解析失败，尝试数组
            LlmResponse arrResult = tryParseJsonArray(cleaned);
            if (arrResult != null) return arrResult;
        } else if (firstSignificant == '[') {
            // 顶层是数组，优先 JSON Array
            LlmResponse arrResult = tryParseJsonArray(cleaned);
            if (arrResult != null) return arrResult;
            // 数组解析失败，尝试对象（兜底）
            LlmResponse objResult = tryParseJsonObject(cleaned);
            if (objResult != null) return objResult;
        } else {
            // 无明确顶层结构，依次尝试
            LlmResponse objResult = tryParseJsonObject(cleaned);
            if (objResult != null) return objResult;
            LlmResponse arrResult = tryParseJsonArray(cleaned);
            if (arrResult != null) return arrResult;
        }

        // 原始文本 fallback
        log.warn("LLM 未输出有效 JSON，降级为原始文本模式。commit 阻断判定可能不准确。");
        log.warn("LLM 原始响应（前200字符）：{}", content.substring(0, Math.min(200, content.length())));
        return new LlmResponse(List.of(), content, null);
    }

    private static char firstNonWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return s.charAt(i);
        }
        return '\0';
    }

    private static LlmResponse tryParseJsonObject(String content) {
        try {
            String jsonObj = extractJsonObject(content);
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
            log.debug("LLM 输出非 JSON 对象格式");
        }
        return null;
    }

    private static LlmResponse tryParseJsonArray(String content) {
        try {
            String json = extractJsonArray(content);
            if (json != null) {
                List<ReviewIssue> issues = JacksonMapper.MAPPER.readValue(json, new TypeReference<List<ReviewIssue>>() {});
                return new LlmResponse(issues, null, null);
            }
        } catch (Exception e) {
            log.debug("LLM 输出非 JSON 数组格式");
        }
        return null;
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
        if (start < 0) return null;
        int end = findMatchingBrace(content, start, '{', '}');
        if (end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }

    static String extractJsonArray(String content) {
        if (content == null) return null;
        int start = content.indexOf('[');
        if (start < 0) return null;
        int end = findMatchingBrace(content, start, '[', ']');
        if (end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }

    private static int findMatchingBrace(String content, int openPos, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = openPos; i < content.length(); i++) {
            char c = content.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
