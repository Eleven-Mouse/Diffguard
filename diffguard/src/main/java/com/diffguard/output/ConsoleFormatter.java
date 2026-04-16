package com.diffguard.output;

import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;

public class ConsoleFormatter {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String GREEN = "\u001B[32m";
    private static final String GRAY = "\u001B[90m";
    private static final String DOUBLE_LINE = "═".repeat(60);

    public static void printReport(ReviewResult result) {
        System.out.println();
        System.out.println(BOLD + DOUBLE_LINE + RESET);
        System.out.println(BOLD + "  Code Review Report - " + result.getSummary() + RESET);
        System.out.println(BOLD + DOUBLE_LINE + RESET);
        System.out.println();

        if (result.getIssues().isEmpty()) {
            System.out.println(GREEN + BOLD + "  ✓ No issues found. Code looks good!" + RESET);
            System.out.println();
            return;
        }

        for (ReviewIssue issue : result.getIssues()) {
            printIssue(issue);
        }

        System.out.println(BOLD + DOUBLE_LINE + RESET);

        // Summary line
        if (result.hasCriticalIssues()) {
            int critical = result.getIssuesBySeverity(Severity.CRITICAL).size();
            System.out.println(RED + BOLD + "  ✗ " + critical + " critical issue(s) found - commit aborted" + RESET);
            System.out.println(GRAY + "  Run with --force to bypass review" + RESET);
        } else {
            System.out.println(GREEN + BOLD + "  ✓ No critical issues - commit allowed" + RESET);
        }

        // Stats
        System.out.println(GRAY + String.format(
                "  Duration: %dms | Tokens: %d | Files: %d",
                result.getReviewDurationMs(),
                result.getTotalTokensUsed(),
                result.getTotalFilesReviewed()) + RESET);
        System.out.println(BOLD + DOUBLE_LINE + RESET);
        System.out.println();
    }

    private static void printIssue(ReviewIssue issue) {
        Severity severity = issue.getSeverity();
        String color = switch (severity) {
            case CRITICAL -> RED;
            case WARNING -> YELLOW;
            case INFO -> BLUE;
        };

        System.out.printf("  %s%s %-10s%s %s:%d%n",
                color, BOLD, severity.getIcon() + " " + severity.getLabel(), RESET,
                issue.getFile(), issue.getLine());

        System.out.printf("    %sType:%s %s%n", GRAY, RESET, issue.getType());
        System.out.printf("    %sMessage:%s %s%n", GRAY, RESET, issue.getMessage());

        if (issue.getSuggestion() != null && !issue.getSuggestion().isBlank()) {
            System.out.printf("    %sSuggestion:%s %s%n", GRAY, RESET, issue.getSuggestion());
        }

        System.out.println();
    }
}
