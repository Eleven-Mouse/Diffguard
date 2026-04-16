package com.diffguard.output;

import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownFormatterTest {

    private ReviewIssue makeIssue(Severity severity, String file, int line, String type, String message, String suggestion) {
        ReviewIssue issue = new ReviewIssue();
        issue.setSeverity(severity);
        issue.setFile(file);
        issue.setLine(line);
        issue.setType(type);
        issue.setMessage(message);
        issue.setSuggestion(suggestion);
        return issue;
    }

    @Nested
    @DisplayName("结构化结果")
    class StructuredResult {

        @Test
        @DisplayName("包含 issues 时生成表格和建议")
        void withIssues() {
            ReviewResult result = new ReviewResult();
            result.addIssue(makeIssue(Severity.CRITICAL, "src/Main.java", 42, "安全", "SQL注入", "使用参数化查询"));
            result.addIssue(makeIssue(Severity.WARNING, "src/Util.java", 10, "风格", "命名不规范", null));
            result.setTotalFilesReviewed(2);
            result.setTotalTokensUsed(500);
            result.setReviewDurationMs(1500);

            String md = MarkdownFormatter.format(result);

            assertTrue(md.contains("DiffGuard AI Code Review"));
            assertTrue(md.contains("SQL注入"));
            assertTrue(md.contains("src/Main.java"));
            assertTrue(md.contains("使用参数化查询"));
            assertTrue(md.contains("| Severity |"));
            assertTrue(md.contains("2 file(s)"));
            assertFalse(md.contains("\u001B[")); // 无 ANSI 转义码
        }

        @Test
        @DisplayName("空 issues 显示无问题")
        void noIssues() {
            ReviewResult result = new ReviewResult();
            result.setTotalFilesReviewed(1);
            result.setTotalTokensUsed(100);
            result.setReviewDurationMs(500);

            String md = MarkdownFormatter.format(result);

            assertTrue(md.contains("No issues found"));
            assertFalse(md.contains("| Severity |"));
        }
    }

    @Nested
    @DisplayName("Raw Report")
    class RawReport {

        @Test
        @DisplayName("raw report 原样输出并含不确定提示")
        void rawReportOutput() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("# 审查报告\n\n## 问题\n1. 某问题");
            result.setTotalFilesReviewed(1);
            result.setTotalTokensUsed(200);
            result.setReviewDurationMs(800);

            String md = MarkdownFormatter.format(result);

            assertTrue(md.contains("审查报告"));
            assertTrue(md.contains("unstructured output"));
        }
    }

    @Nested
    @DisplayName("格式化细节")
    class FormattingDetails {

        @Test
        @DisplayName("管道符被转义")
        void pipeEscaped() {
            ReviewResult result = new ReviewResult();
            result.addIssue(makeIssue(Severity.INFO, "A.java", 1, "t", "a | b", "c | d"));
            result.setTotalFilesReviewed(1);
            result.setTotalTokensUsed(0);
            result.setReviewDurationMs(0);

            String md = MarkdownFormatter.format(result);

            assertTrue(md.contains("a \\| b"));
            assertTrue(md.contains("c \\| d"));
        }

        @Test
        @DisplayName("无 ANSI 转义码")
        void noAnsiCodes() {
            ReviewResult result = new ReviewResult();
            result.addIssue(makeIssue(Severity.WARNING, "A.java", 1, "t", "msg", "sug"));
            result.setTotalFilesReviewed(1);
            result.setTotalTokensUsed(0);
            result.setReviewDurationMs(0);

            String md = MarkdownFormatter.format(result);
            assertFalse(md.contains("\u001B["));
        }

        @Test
        @DisplayName("包含 footer 统计信息")
        void footerStats() {
            ReviewResult result = new ReviewResult();
            result.setTotalFilesReviewed(3);
            result.setTotalTokensUsed(1000);
            result.setReviewDurationMs(2500);

            String md = MarkdownFormatter.format(result);

            assertTrue(md.contains("3 file(s)"));
            assertTrue(md.contains("1000 tokens"));
            assertTrue(md.contains("2.5s"));
        }
    }
}
