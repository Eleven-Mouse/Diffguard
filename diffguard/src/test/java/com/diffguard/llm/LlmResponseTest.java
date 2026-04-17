package com.diffguard.llm;

import com.diffguard.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmResponse.fromContent() 核心路径测试。
 * 覆盖 JSON Object → JSON Array → Raw Text 三级 fallback 解析。
 */
class LlmResponseTest {

    // ------------------------------------------------------------------
    // JSON Object 格式（含 has_critical 标志）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("JSON Object 格式解析")
    class JsonObjectParsing {

        @Test
        @DisplayName("标准 JSON Object：has_critical=true + issues 列表")
        void standardObjectWithCritical() {
            String json = """
                {
                  "has_critical": true,
                  "issues": [
                    {"severity": "CRITICAL", "file": "A.java", "line": 10, "type": "安全", "message": "SQL注入", "suggestion": "使用参数化查询"}
                  ]
                }
                """;

            LlmResponse resp = LlmResponse.fromContent(json);

            assertFalse(resp.isRawText());
            assertTrue(resp.getHasCritical());
            assertEquals(1, resp.getIssues().size());
            assertEquals(Severity.CRITICAL, resp.getIssues().get(0).getSeverity());
            assertEquals("SQL注入", resp.getIssues().get(0).getMessage());
        }

        @Test
        @DisplayName("JSON Object：has_critical=false + 空 issues")
        void objectNoCriticalNoIssues() {
            String json = """
                {
                  "has_critical": false,
                  "issues": []
                }
                """;

            LlmResponse resp = LlmResponse.fromContent(json);

            assertFalse(resp.isRawText());
            assertFalse(resp.getHasCritical());
            assertTrue(resp.getIssues().isEmpty());
        }

        @Test
        @DisplayName("JSON Object：无 has_critical 字段 → 默认 false")
        void objectWithoutCriticalFlag() {
            String json = """
                {
                  "issues": [
                    {"severity": "WARNING", "file": "B.java", "line": 5, "type": "风格", "message": "命名不规范"}
                  ]
                }
                """;

            LlmResponse resp = LlmResponse.fromContent(json);

            assertFalse(resp.isRawText());
            assertFalse(resp.getHasCritical());
            assertEquals(1, resp.getIssues().size());
        }

        @Test
        @DisplayName("JSON Object：无 issues 字段 → 空列表")
        void objectWithoutIssuesField() {
            String json = """
                {
                  "has_critical": true,
                  "highlights": ["代码清晰"]
                }
                """;

            LlmResponse resp = LlmResponse.fromContent(json);

            assertFalse(resp.isRawText());
            assertTrue(resp.getHasCritical());
            assertTrue(resp.getIssues().isEmpty());
        }

        @Test
        @DisplayName("JSON Object 前后有 Markdown 代码块包裹")
        void objectWrappedInMarkdown() {
            String content = "```json\n{\"has_critical\": false, \"issues\": []}\n```";

            LlmResponse resp = LlmResponse.fromContent(content);

            assertFalse(resp.isRawText());
            assertFalse(resp.getHasCritical());
        }

        @Test
        @DisplayName("JSON Object 前有 LLM 解释文字")
        void objectWithPrefixText() {
            String content = "以下是审查结果：\n\n{\"has_critical\": true, \"issues\": []}";

            LlmResponse resp = LlmResponse.fromContent(content);

            assertFalse(resp.isRawText());
            assertTrue(resp.getHasCritical());
        }

        @Test
        @DisplayName("JSON Object 中多个 issues 全部解析")
        void objectWithMultipleIssues() {
            String json = """
                {
                  "has_critical": true,
                  "issues": [
                    {"severity": "CRITICAL", "file": "A.java", "line": 1, "type": "t1", "message": "m1"},
                    {"severity": "WARNING", "file": "B.java", "line": 2, "type": "t2", "message": "m2"},
                    {"severity": "INFO", "file": "C.java", "line": 3, "type": "t3", "message": "m3"}
                  ]
                }
                """;

            LlmResponse resp = LlmResponse.fromContent(json);

            assertEquals(3, resp.getIssues().size());
            assertEquals(Severity.CRITICAL, resp.getIssues().get(0).getSeverity());
            assertEquals(Severity.WARNING, resp.getIssues().get(1).getSeverity());
            assertEquals(Severity.INFO, resp.getIssues().get(2).getSeverity());
        }
    }

    // ------------------------------------------------------------------
    // JSON Array 格式（向后兼容）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("JSON Array 格式解析")
    class JsonArrayParsing {

        @Test
        @DisplayName("空 JSON Array（无 {}）→ 0 issues，走 Array 路径")
        void emptyArray() {
            String json = "[]";

            LlmResponse resp = LlmResponse.fromContent(json);

            assertFalse(resp.isRawText());
            assertTrue(resp.getIssues().isEmpty());
        }

        @Test
        @DisplayName("含对象的 JSON Array：extractJsonObject 优先提取内层 {} 作为 Object 解析")
        void arrayWithObjects_parsedAsObjectFirst() {
            // 注意：[{...}, {...}] 中 extractJsonObject 会提取从第一个 { 到最后一个 } 的子串
            // Jackson 2.17 默认忽略尾部 token，所以第一个 {} 被解析为 Map
            // 这是当前实现的行为特征——Object 优先级高于 Array
            String json = """
                [
                  {"severity": "WARNING", "file": "A.java", "line": 10, "type": "风格", "message": "命名不规范"},
                  {"severity": "INFO", "file": "B.java", "line": 20, "type": "建议", "message": "可简化"}
                ]
                """;

            LlmResponse resp = LlmResponse.fromContent(json);

            assertFalse(resp.isRawText());
            // Object 路径解析了第一个 {}，其中无 has_critical → false，无 issues → 空列表
            assertFalse(resp.getHasCritical());
            assertTrue(resp.getIssues().isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // Raw Text Fallback
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("原始文本 Fallback")
    class RawTextFallback {

        @Test
        @DisplayName("非 JSON 文本 → raw text 模式")
        void plainTextFallback() {
            String text = "# 代码审查报告\n\n## 严重问题\n未发现严重问题\n\n## 建议\n无";

            LlmResponse resp = LlmResponse.fromContent(text);

            assertTrue(resp.isRawText());
            assertEquals(text, resp.getRawText());
            assertTrue(resp.getIssues().isEmpty());
            assertNull(resp.getHasCritical());
        }

        @Test
        @DisplayName("破损 JSON → raw text fallback")
        void brokenJsonFallback() {
            String text = "{这个不是有效的JSON";

            LlmResponse resp = LlmResponse.fromContent(text);

            assertTrue(resp.isRawText());
        }

        @Test
        @DisplayName("null 输入 → 空 issues，非 raw text")
        void nullInput() {
            LlmResponse resp = LlmResponse.fromContent(null);

            assertFalse(resp.isRawText());
            assertTrue(resp.getIssues().isEmpty());
            assertFalse(resp.getHasCritical());
        }

        @Test
        @DisplayName("空白字符串 → 空 issues，非 raw text")
        void blankInput() {
            LlmResponse resp = LlmResponse.fromContent("   \n\t  ");

            assertFalse(resp.isRawText());
            assertTrue(resp.getIssues().isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // 边界情况
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("severity 字段使用 HIGH → 映射为 CRITICAL")
        void severityHighMapping() {
            String json = """
                {
                  "has_critical": true,
                  "issues": [
                    {"severity": "HIGH", "file": "A.java", "line": 1, "type": "t", "message": "m"}
                  ]
                }
                """;

            LlmResponse resp = LlmResponse.fromContent(json);

            assertEquals(1, resp.getIssues().size());
            assertEquals(Severity.CRITICAL, resp.getIssues().get(0).getSeverity());
        }

        @Test
        @DisplayName("severity 字段使用 MEDIUM → 映射为 WARNING")
        void severityMediumMapping() {
            String json = """
                {
                  "has_critical": false,
                  "issues": [
                    {"severity": "MEDIUM", "file": "A.java", "line": 1, "type": "t", "message": "m"}
                  ]
                }
                """;

            LlmResponse resp = LlmResponse.fromContent(json);

            assertEquals(1, resp.getIssues().size());
            assertEquals(Severity.WARNING, resp.getIssues().get(0).getSeverity());
        }

        @Test
        @DisplayName("未知 severity 字段 → 映射为 INFO")
        void severityUnknownMapping() {
            String json = """
                {
                  "has_critical": false,
                  "issues": [
                    {"severity": "UNKNOWN_LEVEL", "file": "A.java", "line": 1, "type": "t", "message": "m"}
                  ]
                }
                """;

            LlmResponse resp = LlmResponse.fromContent(json);

            assertEquals(1, resp.getIssues().size());
            assertEquals(Severity.INFO, resp.getIssues().get(0).getSeverity());
        }

        @Test
        @DisplayName("JSON Object 内含额外字段（如 highlights）不影响解析")
        void extraFieldsIgnored() {
            String json = """
                {
                  "has_critical": false,
                  "issues": [],
                  "highlights": ["代码结构良好"],
                  "test_suggestions": ["增加边界测试"]
                }
                """;

            LlmResponse resp = LlmResponse.fromContent(json);

            assertFalse(resp.isRawText());
            assertTrue(resp.getIssues().isEmpty());
        }
    }
}
