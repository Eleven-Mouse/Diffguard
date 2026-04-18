package com.diffguard.output;

import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;

import java.util.Objects;

import static com.diffguard.output.AnsiColors.*;

/**
 * 专业级审查报告输出。
 * <p>
 * 提供清晰的视觉层级、颜色区分和统一分隔线。
 */
public final class ReviewReportPrinter {

    private static final String THIN_LINE = "─".repeat(56);
    private static final String THICK_LINE = "━".repeat(56);

    private ReviewReportPrinter() {}

    public static void printReport(ReviewResult result) {
        Objects.requireNonNull(result, "result");
        if (result.isRawReport()) {
            printRawReport(result);
            return;
        }

        printHeader(result);
        if (result.getIssues().isEmpty()) {
            printCleanVerdict();
            printStats(result);
            printFooter();
            return;
        }

        printIssuesBySeverity(result);
        printVerdict(result);
        printStats(result);
        printFooter();
    }

    static void printHeader(ReviewResult result) {
        TerminalUI.println();
        TerminalUI.println("  " + CYAN + BOLD + THICK_LINE + RESET);
        TerminalUI.println("  " + BOLD + "  DiffGuard Review Report" + RESET);
        TerminalUI.println("  " + GRAY + TerminalUI.sanitize(Objects.toString(result.getSummary(), "")) + RESET);
        TerminalUI.println("  " + CYAN + BOLD + THICK_LINE + RESET);
        TerminalUI.println();
    }

    static void printCleanVerdict() {
        TerminalUI.println("  " + GREEN + BOLD + "  ✓ All clear" + RESET + GRAY + " — no issues found" + RESET);
        TerminalUI.println();
    }

    static void printIssuesBySeverity(ReviewResult result) {
        Severity[] order = {Severity.CRITICAL, Severity.WARNING, Severity.INFO};
        for (Severity sev : order) {
            var issues = result.getIssuesBySeverity(sev);
            if (issues.isEmpty()) continue;
            printSeverityGroup(sev, issues);
        }
    }

    static void printSeverityGroup(Severity severity, java.util.List<ReviewIssue> issues) {
        String color = severityColor(severity);
        String label = severity.getLabel();
        String icon = severity.getIcon();

        TerminalUI.println("  " + color + BOLD + "  " + icon + " " + label + " (" + issues.size() + ")" + RESET);
        TerminalUI.println("  " + GRAY + "  " + THIN_LINE + RESET);

        for (int i = 0; i < issues.size(); i++) {
            printIssue(issues.get(i), severity, i == issues.size() - 1);
        }
        TerminalUI.println();
    }

    static void printIssue(ReviewIssue issue, Severity severity, boolean last) {
        String color = severityColor(severity);
        String marker = last ? "  ╰─" : "  ├─";

        String file = TerminalUI.sanitize(Objects.toString(issue.getFile(), ""));
        String line = Objects.toString(issue.getLine(), "");
        String type = TerminalUI.sanitize(Objects.toString(issue.getType(), ""));
        String message = TerminalUI.sanitize(Objects.toString(issue.getMessage(), ""));
        String suggestion = issue.getSuggestion();

        TerminalUI.println("  " + GRAY + marker + RESET + " "
                + BOLD + truncate(file, 40) + RESET
                + GRAY + ":" + RESET + line
                + "  " + GRAY + type + RESET);

        TerminalUI.println("  " + GRAY + (last ? "    " : "  │ ") + RESET + color + message + RESET);

        if (suggestion != null && !suggestion.isBlank()) {
            TerminalUI.println("  " + GRAY + (last ? "    " : "  │ ") + RESET
                    + DIM + "→ " + TerminalUI.sanitize(suggestion) + RESET);
        }
    }

    static void printVerdict(ReviewResult result) {
        TerminalUI.println("  " + GRAY + THIN_LINE + RESET);

        if (result.hasCriticalIssues()) {
            int critical = result.getIssuesBySeverity(Severity.CRITICAL).size();
            TerminalUI.println("  " + RED + BOLD + "  ✗ BLOCKED" + RESET
                    + RED + " — " + critical + " critical issue(s) found" + RESET);
            TerminalUI.println("  " + GRAY + "    Use --force to bypass" + RESET);
        } else {
            TerminalUI.println("  " + GREEN + BOLD + "  ✓ PASSED" + RESET
                    + GREEN + " — no critical issues" + RESET);
        }
        TerminalUI.println();
    }

    static void printStats(ReviewResult result) {
        int critical = result.getIssuesBySeverity(Severity.CRITICAL).size();
        int warning = result.getIssuesBySeverity(Severity.WARNING).size();
        int info = result.getIssuesBySeverity(Severity.INFO).size();
        int total = result.getIssues().size();

        TerminalUI.println("  " + GRAY + "  ┌──────────────────────────────────────────────────┐" + RESET);
        printStatRow("Files", String.valueOf(result.getTotalFilesReviewed()));
        printStatRow("Issues", formatIssueCount(total, critical, warning, info));
        printStatRow("Duration", formatDuration(result.getReviewDurationMs()));
        printStatRow("Tokens", String.valueOf(result.getTotalTokensUsed()));
        TerminalUI.println("  " + GRAY + "  └──────────────────────────────────────────────────┘" + RESET);
    }

    private static void printStatRow(String label, String value) {
        TerminalUI.printf("  %s  │%s %-10s%-39s%s│%n",
                GRAY, RESET, label, BOLD + value + RESET, GRAY);
    }

    private static String formatIssueCount(int total, int critical, int warning, int info) {
        StringBuilder sb = new StringBuilder();
        sb.append(total);
        if (total > 0) {
            sb.append(" (");
            if (critical > 0) sb.append(RED).append(critical).append("C").append(RESET).append(" ");
            if (warning > 0) sb.append(YELLOW).append(warning).append("W").append(RESET).append(" ");
            if (info > 0) sb.append(BLUE).append(info).append("I").append(RESET);
            sb.append(")");
        }
        return sb.toString();
    }

    static void printFooter() {
        TerminalUI.println("  " + GRAY + THIN_LINE + RESET);
        TerminalUI.println();
    }

    static void printRawReport(ReviewResult result) {
        TerminalUI.println();
        TerminalUI.println(TerminalUI.sanitize(result.getRawReport()));
        TerminalUI.println();

        printStats(result);

        if (result.isUncertainResult()) {
            TerminalUI.println("  " + YELLOW + BOLD + "⚠ Unstructured AI output — please review manually" + RESET);
        } else if (result.hasCriticalIssues()) {
            TerminalUI.println("  " + RED + BOLD + "✗ Critical issues found — commit blocked" + RESET);
            TerminalUI.println("  " + GRAY + "  Use --force to bypass" + RESET);
        } else {
            TerminalUI.println("  " + GREEN + BOLD + "✓ No critical issues — commit allowed" + RESET);
        }
        TerminalUI.println();
    }

    private static String severityColor(Severity severity) {
        return switch (severity) {
            case CRITICAL -> RED;
            case WARNING -> YELLOW;
            case INFO -> BLUE;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "..." + s.substring(s.length() - max + 3);
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "-";
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }
}
