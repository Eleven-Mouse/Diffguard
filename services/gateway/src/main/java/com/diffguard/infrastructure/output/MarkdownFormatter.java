package com.diffguard.infrastructure.output;

import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.domain.review.model.Severity;

/**
 * 将 ReviewResult 格式化为 GitHub Flavored Markdown，
 * 用于作为 PR Comment 发布。
 */
public class MarkdownFormatter {

    public static String format(ReviewResult result) {
        StringBuilder sb = new StringBuilder();

        sb.append("## DiffGuard AI Code Review\n\n");

        if (result.isRawReport()) {
            sb.append(result.getRawReport()).append("\n\n");
        } else if (result.getIssues().isEmpty()) {
            sb.append("**No issues found.** Code looks good!\n\n");
        } else {
            appendSeveritySummary(sb, result);
            sb.append("\n");

            sb.append("| Severity | File | Line | Type | Message |\n");
            sb.append("|----------|------|------|------|--------|\n");
            for (ReviewIssue issue : result.getIssues()) {
                sb.append("| ").append(severityBadge(issue.getSeverity()))
                        .append(" | `").append(escapeTable(issue.getFile())).append("`")
                        .append(" | ").append(issue.getLine())
                        .append(" | ").append(escapeTable(issue.getType()))
                        .append(" | ").append(escapeTable(issue.getMessage()))
                        .append(" |\n");
            }
            sb.append("\n");

            for (ReviewIssue issue : result.getIssues()) {
                if (issue.getSuggestion() != null && !issue.getSuggestion().isBlank()) {
                    sb.append("### ").append(severityBadge(issue.getSeverity()))
                            .append(" `").append(issue.getFile()).append(":").append(issue.getLine()).append("`")
                            .append(" - ").append(issue.getType()).append("\n\n");
                    sb.append("**Issue:** ").append(escapeTable(issue.getMessage())).append("\n\n");
                    sb.append("**Suggestion:** ").append(escapeTable(issue.getSuggestion())).append("\n\n");
                }
            }
        }

        if (result.isUncertainResult()) {
            sb.append("> **Note:** AI returned unstructured output. Please review the report above manually.\n\n");
        }

        sb.append("---\n");
        sb.append("*Review powered by [DiffGuard](https://github.com/diffguard) | ");
        sb.append(result.getTotalFilesReviewed()).append(" file(s) | ");
        sb.append(result.getTotalTokensUsed()).append(" tokens | ");
        sb.append(FormatUtils.formatDuration(result.getReviewDurationMs())).append("*\n");

        return sb.toString();
    }

    private static void appendSeveritySummary(StringBuilder sb, ReviewResult result) {
        int critical = result.getIssuesBySeverity(Severity.CRITICAL).size();
        int warning = result.getIssuesBySeverity(Severity.WARNING).size();
        int info = result.getIssuesBySeverity(Severity.INFO).size();

        sb.append("**").append(result.getSummary()).append("**\n\n");

        if (critical > 0) sb.append(":red_circle: **").append(critical).append(" Critical**  ");
        if (warning > 0) sb.append(":yellow_circle: **").append(warning).append(" Warning**  ");
        if (info > 0) sb.append(":blue_circle: **").append(info).append(" Info**  ");
        if (critical + warning + info > 0) sb.append("\n");
    }

    private static String severityBadge(Severity s) {
        return switch (s) {
            case CRITICAL -> ":red_circle: Critical";
            case WARNING -> ":yellow_circle: Warning";
            case INFO -> ":blue_circle: Info";
        };
    }

    private static String escapeTable(String text) {
        if (text == null) return "";
        return text.replace("|", "\\|");
    }
}
