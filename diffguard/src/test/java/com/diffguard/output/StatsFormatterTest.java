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

@DisplayName("StatsFormatter")
class StatsFormatterTest {

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

    private ReviewResult makeResult(int files, int tokens, long durationMs, ReviewIssue... issues) {
        ReviewResult result = new ReviewResult();
        for (ReviewIssue issue : issues) {
            result.addIssue(issue);
        }
        result.setTotalFilesReviewed(files);
        result.setTotalTokensUsed(tokens);
        result.setReviewDurationMs(durationMs);
        return result;
    }

    private ReviewIssue makeIssue(Severity severity) {
        ReviewIssue issue = new ReviewIssue();
        issue.setSeverity(severity);
        issue.setFile("A.java");
        issue.setLine(1);
        issue.setType("类型");
        issue.setMessage("消息");
        return issue;
    }

    // ------------------------------------------------------------------
    // printStats 输出内容
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("printStats 输出")
    class PrintStats {

        @Test
        @DisplayName("统计框包含文件数")
        void containsFileCount() {
            ReviewResult result = makeResult(5, 1000, 2000);
            String output = captureOutput(() -> StatsFormatter.printStats(result));
            assertTrue(output.contains("5"));
        }

        @Test
        @DisplayName("统计框包含问题数（按严重级别分解）")
        void containsIssueBreakdown() {
            ReviewResult result = makeResult(1, 500, 1000,
                    makeIssue(Severity.CRITICAL), makeIssue(Severity.WARNING), makeIssue(Severity.INFO));

            String output = captureOutput(() -> StatsFormatter.printStats(result));

            assertTrue(output.contains("3"));  // total issues
        }

        @Test
        @DisplayName("统计框包含 Token 数")
        void containsTokenCount() {
            ReviewResult result = makeResult(1, 2500, 1000);
            String output = captureOutput(() -> StatsFormatter.printStats(result));
            assertTrue(output.contains("2500"));
        }

        @Test
        @DisplayName("统计框包含耗时")
        void containsDuration() {
            ReviewResult result = makeResult(1, 100, 1500);
            String output = captureOutput(() -> StatsFormatter.printStats(result));
            assertTrue(output.contains("1.5s"));
        }
    }

    // ------------------------------------------------------------------
    // formatDuration 间接验证
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("formatDuration 间接验证")
    class DurationFormatting {

        @Test
        @DisplayName("耗时 0ms → 显示 -")
        void zeroDuration() {
            ReviewResult result = makeResult(1, 100, 0);
            String output = captureOutput(() -> StatsFormatter.printStats(result));
            assertTrue(output.contains("-"));
        }

        @Test
        @DisplayName("耗时 500ms → 显示 500ms")
        void subSecondDuration() {
            ReviewResult result = makeResult(1, 100, 500);
            String output = captureOutput(() -> StatsFormatter.printStats(result));
            assertTrue(output.contains("500ms"));
        }

        @Test
        @DisplayName("耗时 1500ms → 显示 1.5s")
        void overSecondDuration() {
            ReviewResult result = makeResult(1, 100, 1500);
            String output = captureOutput(() -> StatsFormatter.printStats(result));
            assertTrue(output.contains("1.5s"));
        }

        @Test
        @DisplayName("耗时 60000ms → 显示 60.0s")
        void longDuration() {
            ReviewResult result = makeResult(1, 100, 60000);
            String output = captureOutput(() -> StatsFormatter.printStats(result));
            assertTrue(output.contains("60.0s"));
        }
    }

    // ------------------------------------------------------------------
    // 边界场景
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("无 issues 的结果 → 统计框正常输出")
        void noIssuesOutput() {
            ReviewResult result = makeResult(3, 800, 500);
            String output = captureOutput(() -> StatsFormatter.printStats(result));
            assertTrue(output.contains("文件数"));
            assertTrue(output.contains("Token"));
        }

        @Test
        @DisplayName("大量 issues → 统计框正常输出")
        void manyIssuesOutput() {
            ReviewResult result = makeResult(10, 5000, 3000);
            for (int i = 0; i < 20; i++) {
                result.addIssue(makeIssue(Severity.WARNING));
            }
            String output = captureOutput(() -> StatsFormatter.printStats(result));
            assertTrue(output.contains("20"));
        }
    }
}
