package com.diffguard.output;

import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;

import static com.diffguard.output.AnsiColors.*;

/**
 * 审查统计信息格式化输出。
 * 在审查完成后输出结构化的统计表格。
 */
public final class StatsFormatter {

    private StatsFormatter() {}

    public static void printStats(ReviewResult result) {
        int critical = result.getIssuesBySeverity(Severity.CRITICAL).size();
        int warning = result.getIssuesBySeverity(Severity.WARNING).size();
        int info = result.getIssuesBySeverity(Severity.INFO).size();
        int total = result.getIssues().size();

        System.out.println(GRAY + "  ┌────────────────────────────────────────────┐" + RESET);
        System.out.printf("%s  │%s 文件数   %-35s%s│%n",
                GRAY, RESET, BOLD + result.getTotalFilesReviewed() + RESET, GRAY);
        System.out.printf("%s  │%s 问题数   %s%-6d%s (🔴%d  🟡%d  🔵%d)%s        │%n",
                GRAY, RESET, BOLD, total, RESET, critical, warning, info, GRAY);
        System.out.printf("%s  │%s 耗时     %-35s%s│%n",
                GRAY, RESET, BOLD + formatDuration(result.getReviewDurationMs()) + RESET, GRAY);
        System.out.printf("%s  │%s Token    %-35s%s│%n",
                GRAY, RESET, BOLD + result.getTotalTokensUsed() + RESET, GRAY);
        System.out.println(GRAY + "  └────────────────────────────────────────────┘" + RESET);
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "-";
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }
}
