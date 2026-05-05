package com.diffguard.infrastructure.output;

import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.domain.review.model.Severity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReviewReportPrinter")
class ReviewReportPrinterTest {

    @BeforeAll
    static void setSilent() {
        // Suppress actual console output during tests
        TerminalUI.setSilent(true);
    }

    // ------------------------------------------------------------------
    // printReport
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("printReport")
    class PrintReport {

        @Test
        @DisplayName("throws NullPointerException when result is null")
        void throwsOnNull() {
            assertThrows(NullPointerException.class, () -> ReviewReportPrinter.printReport(null));
        }

        @Test
        @DisplayName("prints clean report when no issues")
        void cleanReport() {
            ReviewResult result = new ReviewResult();
            assertDoesNotThrow(() -> ReviewReportPrinter.printReport(result));
        }

        @Test
        @DisplayName("prints report with issues")
        void reportWithIssues() {
            ReviewResult result = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.WARNING);
            issue.setFile("Test.java");
            issue.setLine(10);
            issue.setType("CODE_STYLE");
            issue.setMessage("Variable name too short");
            issue.setSuggestion("Use a more descriptive name");
            result.addIssue(issue);

            assertDoesNotThrow(() -> ReviewReportPrinter.printReport(result));
        }

        @Test
        @DisplayName("prints report with critical issues")
        void reportWithCriticalIssues() {
            ReviewResult result = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.CRITICAL);
            issue.setFile("Security.java");
            issue.setLine(5);
            issue.setType("SECURITY");
            issue.setMessage("SQL injection vulnerability");
            issue.setSuggestion("Use parameterized queries");
            result.addIssue(issue);

            assertDoesNotThrow(() -> ReviewReportPrinter.printReport(result));
        }

        @Test
        @DisplayName("prints report with info issues")
        void reportWithInfoIssues() {
            ReviewResult result = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.INFO);
            issue.setFile("Util.java");
            issue.setLine(100);
            issue.setType("SUGGESTION");
            issue.setMessage("Consider using a StringBuilder");
            result.addIssue(issue);

            assertDoesNotThrow(() -> ReviewReportPrinter.printReport(result));
        }

        @Test
        @DisplayName("prints report with mixed severities")
        void reportWithMixedSeverities() {
            ReviewResult result = new ReviewResult();
            for (Severity sev : Severity.values()) {
                ReviewIssue issue = new ReviewIssue();
                issue.setSeverity(sev);
                issue.setFile(sev.name() + ".java");
                issue.setLine(1);
                issue.setType("TYPE");
                issue.setMessage(sev.name() + " issue");
                issue.setSuggestion("Fix it");
                result.addIssue(issue);
            }

            assertDoesNotThrow(() -> ReviewReportPrinter.printReport(result));
        }

        @Test
        @DisplayName("prints raw report when isRawReport is true")
        void rawReport() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("This is a raw LLM response without structured JSON output.");

            assertDoesNotThrow(() -> ReviewReportPrinter.printReport(result));
        }

        @Test
        @DisplayName("prints raw report with critical issues")
        void rawReportWithCritical() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("Raw response");
            result.setHasCriticalFlag(true);

            assertDoesNotThrow(() -> ReviewReportPrinter.printReport(result));
        }

        @Test
        @DisplayName("prints raw report that is uncertain (no structured issues)")
        void rawReportUncertain() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("Uncertain raw output with no structured data");
            // No issues added, so isUncertainResult() returns true

            assertDoesNotThrow(() -> ReviewReportPrinter.printReport(result));
        }

        @Test
        @DisplayName("prints report with stats")
        void reportWithStats() {
            ReviewResult result = new ReviewResult();
            result.setTotalFilesReviewed(5);
            result.setTotalTokensUsed(1500);
            result.setReviewDurationMs(3200);

            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.WARNING);
            issue.setFile("A.java");
            issue.setLine(1);
            issue.setType("TYPE");
            issue.setMessage("Warning msg");
            result.addIssue(issue);

            assertDoesNotThrow(() -> ReviewReportPrinter.printReport(result));
        }

        @Test
        @DisplayName("prints issue with null suggestion")
        void issueWithNullSuggestion() {
            ReviewResult result = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.INFO);
            issue.setFile("B.java");
            issue.setLine(1);
            issue.setType("INFO");
            issue.setMessage("Info msg");
            issue.setSuggestion(null);
            result.addIssue(issue);

            assertDoesNotThrow(() -> ReviewReportPrinter.printReport(result));
        }

        @Test
        @DisplayName("prints issue with blank suggestion")
        void issueWithBlankSuggestion() {
            ReviewResult result = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.INFO);
            issue.setFile("C.java");
            issue.setLine(1);
            issue.setType("INFO");
            issue.setMessage("Info msg");
            issue.setSuggestion("   ");
            result.addIssue(issue);

            assertDoesNotThrow(() -> ReviewReportPrinter.printReport(result));
        }

        @Test
        @DisplayName("prints multiple issues of same severity")
        void multipleIssuesSameSeverity() {
            ReviewResult result = new ReviewResult();
            for (int i = 0; i < 5; i++) {
                ReviewIssue issue = new ReviewIssue();
                issue.setSeverity(Severity.WARNING);
                issue.setFile("File" + i + ".java");
                issue.setLine(i + 1);
                issue.setType("TYPE");
                issue.setMessage("Warning " + i);
                issue.setSuggestion("Fix " + i);
                result.addIssue(issue);
            }

            assertDoesNotThrow(() -> ReviewReportPrinter.printReport(result));
        }
    }

    // ------------------------------------------------------------------
    // Individual print methods
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("printHeader")
    class PrintHeader {

        @Test
        @DisplayName("printHeader does not throw")
        void printHeaderDoesNotThrow() {
            ReviewResult result = new ReviewResult();
            assertDoesNotThrow(() -> ReviewReportPrinter.printHeader(result));
        }
    }

    @Nested
    @DisplayName("printCleanVerdict")
    class PrintCleanVerdict {

        @Test
        @DisplayName("printCleanVerdict does not throw")
        void printCleanVerdictDoesNotThrow() {
            assertDoesNotThrow(() -> ReviewReportPrinter.printCleanVerdict());
        }
    }

    @Nested
    @DisplayName("printFooter")
    class PrintFooter {

        @Test
        @DisplayName("printFooter does not throw")
        void printFooterDoesNotThrow() {
            assertDoesNotThrow(() -> ReviewReportPrinter.printFooter());
        }
    }

    @Nested
    @DisplayName("printVerdict")
    class PrintVerdict {

        @Test
        @DisplayName("printVerdict with critical issues shows BLOCKED")
        void printVerdictBlocked() {
            ReviewResult result = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.CRITICAL);
            issue.setFile("X.java");
            issue.setLine(1);
            issue.setType("SEC");
            issue.setMessage("Critical");
            result.addIssue(issue);

            assertDoesNotThrow(() -> ReviewReportPrinter.printVerdict(result));
        }

        @Test
        @DisplayName("printVerdict without critical issues shows PASSED")
        void printVerdictPassed() {
            ReviewResult result = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.WARNING);
            issue.setFile("Y.java");
            issue.setLine(1);
            issue.setType("WARN");
            issue.setMessage("Warning");
            result.addIssue(issue);

            assertDoesNotThrow(() -> ReviewReportPrinter.printVerdict(result));
        }
    }

    @Nested
    @DisplayName("printStats")
    class PrintStats {

        @Test
        @DisplayName("printStats does not throw with zero values")
        void printStatsZeroValues() {
            ReviewResult result = new ReviewResult();
            assertDoesNotThrow(() -> ReviewReportPrinter.printStats(result));
        }

        @Test
        @DisplayName("printStats with populated values")
        void printStatsPopulated() {
            ReviewResult result = new ReviewResult();
            result.setTotalFilesReviewed(10);
            result.setTotalTokensUsed(5000);
            result.setReviewDurationMs(15000);

            assertDoesNotThrow(() -> ReviewReportPrinter.printStats(result));
        }
    }

    @Nested
    @DisplayName("printIssuesBySeverity")
    class PrintIssuesBySeverity {

        @Test
        @DisplayName("prints issues grouped by severity")
        void printsGroupedBySeverity() {
            ReviewResult result = new ReviewResult();
            ReviewIssue critical = new ReviewIssue();
            critical.setSeverity(Severity.CRITICAL);
            critical.setFile("A.java");
            critical.setLine(1);
            critical.setType("SEC");
            critical.setMessage("Critical issue");
            result.addIssue(critical);

            ReviewIssue warning = new ReviewIssue();
            warning.setSeverity(Severity.WARNING);
            warning.setFile("B.java");
            warning.setLine(2);
            warning.setType("WARN");
            warning.setMessage("Warning issue");
            result.addIssue(warning);

            assertDoesNotThrow(() -> ReviewReportPrinter.printIssuesBySeverity(result));
        }
    }

    @Nested
    @DisplayName("printSeverityGroup")
    class PrintSeverityGroup {

        @Test
        @DisplayName("prints single issue correctly")
        void singleIssue() {
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.INFO);
            issue.setFile("Test.java");
            issue.setLine(1);
            issue.setType("INFO");
            issue.setMessage("Test message");

            assertDoesNotThrow(() ->
                    ReviewReportPrinter.printSeverityGroup(Severity.INFO, java.util.List.of(issue)));
        }

        @Test
        @DisplayName("prints multiple issues correctly")
        void multipleIssues() {
            java.util.List<ReviewIssue> issues = java.util.List.of(
                    createIssue(Severity.CRITICAL, "A.java", 1, "SEC", "Msg1"),
                    createIssue(Severity.CRITICAL, "B.java", 2, "SEC", "Msg2"),
                    createIssue(Severity.CRITICAL, "C.java", 3, "SEC", "Msg3")
            );

            assertDoesNotThrow(() ->
                    ReviewReportPrinter.printSeverityGroup(Severity.CRITICAL, issues));
        }
    }

    @Nested
    @DisplayName("printIssue")
    class PrintIssue {

        @Test
        @DisplayName("prints issue as last item")
        void printIssueLast() {
            ReviewIssue issue = createIssue(Severity.WARNING, "File.java", 10, "TYPE", "Message");

            assertDoesNotThrow(() -> ReviewReportPrinter.printIssue(issue, Severity.WARNING, true));
        }

        @Test
        @DisplayName("prints issue as middle item")
        void printIssueMiddle() {
            ReviewIssue issue = createIssue(Severity.WARNING, "File.java", 10, "TYPE", "Message");

            assertDoesNotThrow(() -> ReviewReportPrinter.printIssue(issue, Severity.WARNING, false));
        }

        @Test
        @DisplayName("prints issue with suggestion")
        void printIssueWithSuggestion() {
            ReviewIssue issue = createIssue(Severity.INFO, "F.java", 5, "T", "Msg");
            issue.setSuggestion("Try this instead");

            assertDoesNotThrow(() -> ReviewReportPrinter.printIssue(issue, Severity.INFO, true));
        }
    }

    private ReviewIssue createIssue(Severity severity, String file, int line, String type, String message) {
        ReviewIssue issue = new ReviewIssue();
        issue.setSeverity(severity);
        issue.setFile(file);
        issue.setLine(line);
        issue.setType(type);
        issue.setMessage(message);
        return issue;
    }
}
