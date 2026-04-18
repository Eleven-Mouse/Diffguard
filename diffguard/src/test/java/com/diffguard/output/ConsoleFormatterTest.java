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

    @Nested
    @DisplayName("Structured report")
    class StructuredReport {

        @Test
        @DisplayName("issues present → outputs header, issues, verdict")
        void reportWithIssues() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.WARNING, "Service.java", 10, "quality", "optimize", "use better method")
            );

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("Service.java"));
            assertTrue(output.contains("10"));
            assertTrue(output.contains("optimize"));
            assertTrue(output.contains("use better method"));
        }

        @Test
        @DisplayName("empty issues → shows all clear")
        void noIssuesFound() {
            ReviewResult result = makeResult();

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("All clear"));
        }

        @Test
        @DisplayName("CRITICAL → shows BLOCKED and --force hint")
        void criticalShowsForceHint() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.CRITICAL, "Dao.java", 42, "security", "SQL injection", "parameterize")
            );
            result.setHasCriticalFlag(true);

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("BLOCKED"));
            assertTrue(output.contains("--force"));
        }

        @Test
        @DisplayName("no CRITICAL → shows PASSED")
        void noCriticalAllowsCommit() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.WARNING, "Util.java", 5, "style", "naming", null)
            );

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("PASSED"));
        }
    }

    @Nested
    @DisplayName("Raw Report mode")
    class RawReport {

        @Test
        @DisplayName("raw text output")
        void rawTextOutput() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("# Review Report\n\nRaw text here");
            result.setTotalFilesReviewed(1);
            result.setReviewDurationMs(500);

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("# Review Report"));
            assertTrue(output.contains("Raw text here"));
        }

        @Test
        @DisplayName("uncertain result → shows manual review hint")
        void uncertainResultShowsWarning() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("some review text");
            result.setTotalFilesReviewed(1);
            result.setReviewDurationMs(500);

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("Unstructured"));
        }
    }

    @Nested
    @DisplayName("Formatting details")
    class FormattingDetails {

        @Test
        @DisplayName("issue with suggestion → outputs suggestion")
        void issueWithSuggestion() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.INFO, "A.java", 1, "highlight", "clear code", "keep it up")
            );

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("keep it up"));
        }

        @Test
        @DisplayName("issue without suggestion → no suggestion line")
        void issueWithoutSuggestion() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.INFO, "A.java", 1, "note", "nice", null)
            );

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertFalse(output.contains("→ nice"));
        }

        @Test
        @DisplayName("output contains ANSI escape codes")
        void outputContainsAnsiCodes() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.WARNING, "A.java", 1, "type", "msg", "suggestion")
            );

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("\u001B["));
        }

        @Test
        @DisplayName("issue output contains file name and line number")
        void issueContainsFileAndLine() {
            ReviewResult result = makeResult(
                    makeIssue(Severity.WARNING, "src/Main.java", 42, "type", "msg", null)
            );

            String output = captureOutput(() -> ConsoleFormatter.printReport(result));

            assertTrue(output.contains("src/Main.java"));
            assertTrue(output.contains("42"));
        }
    }
}
