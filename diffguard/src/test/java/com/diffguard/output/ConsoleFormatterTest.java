package com.diffguard.output;

import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConsoleFormatter")
class ConsoleFormatterTest {

    private String captureOutput(Runnable action) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
            action.run();
            return baos.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(originalOut);
        }
    }

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

    private ReviewResult makeResult(ReviewIssue... issues) {
        ReviewResult result = new ReviewResult();
        for (ReviewIssue issue : issues) {
            result.addIssue(issue);
        }
        result.setTotalFilesReviewed(2);
        result.setTotalTokensUsed(500);
        result.setReviewDurationMs(1500);
        return result;
    }

    // ------------------------------------------------------------------
    // 结构化报告
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("结构化报告")
    class StructuredReport {

        @Test
        @DisplayName("含 issues → 输出标题、issues、总结行")
        void reportWithIssues() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.WARNING, "Service.java", 10, "代码质量", "建议优化", "使用更好的方法")
            );

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("代码审查报告"));
            assertTrue(output.contains("Service.java"));
            assertTrue(output.contains("10"));
            assertTrue(output.contains("建议优化"));
            assertTrue(output.contains("使用更好的方法"));
        }

        @Test
        @DisplayName("空 issues → 显示未发现问题")
        void noIssuesFound() {
            ReviewResult result = makeResult();

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("未发现问题"));
        }

        @Test
        @DisplayName("含 CRITICAL → 显示提交中止和 --force 提示")
        void criticalShowsForceHint() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.CRITICAL, "Dao.java", 42, "安全漏洞", "SQL注入", "参数化查询")
            );
            result.setHasCriticalFlag(true);

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("提交已中止"));
            assertTrue(output.contains("--force"));
        }

        @Test
        @DisplayName("无 CRITICAL → 显示允许提交")
        void noCriticalAllowsCommit() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.WARNING, "Util.java", 5, "代码风格", "命名不规范", null)
            );

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("允许提交"));
        }
    }

    // ------------------------------------------------------------------
    // Raw Report 模式
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Raw Report 模式")
    class RawReport {

        @Test
        @DisplayName("原始文本直接输出")
        void rawTextOutput() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("# 审查报告\n\n这是原始文本");
            result.setTotalFilesReviewed(1);
            result.setReviewDurationMs(500);

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("# 审查报告"));
            assertTrue(output.contains("这是原始文本"));
        }

        @Test
        @DisplayName("不确定结果（raw report + 无 hasCritical 标记）→ 显示人工审阅提示")
        void uncertainResultShowsWarning() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("一些审查文本");
            result.setTotalFilesReviewed(1);
            result.setReviewDurationMs(500);

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("人工审阅"));
        }
    }

    // ------------------------------------------------------------------
    // 格式化细节
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("格式化细节")
    class FormattingDetails {

        @Test
        @DisplayName("issue 带 suggestion → 输出建议行")
        void issueWithSuggestion() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.INFO, "A.java", 1, "亮点", "代码清晰", "保持良好风格")
            );

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("保持良好风格"));
        }

        @Test
        @DisplayName("issue 无 suggestion → 不输出建议行")
        void issueWithoutSuggestion() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.INFO, "A.java", 1, "提示", "不错", null)
            );

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertFalse(output.contains("建议："));
        }

        @Test
        @DisplayName("输出包含 ANSI 转义码")
        void outputContainsAnsiCodes() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.WARNING, "A.java", 1, "类型", "消息", "建议")
            );

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("\u001B["));
        }

        @Test
        @DisplayName("issue 输出包含文件名和行号")
        void issueContainsFileAndLine() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.WARNING, "src/Main.java", 42, "类型", "消息", null)
            );

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("src/Main.java"));
            assertTrue(output.contains("42"));
        }
    }
}
