package com.diffguard.output;

import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;

import static com.diffguard.output.AnsiColors.*;

public class ConsoleFormatter {

    private static final String DOUBLE_LINE = "═".repeat(60);

    public static void printReport(ReviewResult result) {
        System.out.println();

        if (result.isRawReport()) {
            printRawReport(result);
            return;
        }

        // 结构化报告格式
        System.out.println(BOLD + DOUBLE_LINE + RESET);
        System.out.println(BOLD + "  代码审查报告 - " + result.getSummary() + RESET);
        System.out.println(BOLD + DOUBLE_LINE + RESET);
        System.out.println();

        if (result.getIssues().isEmpty()) {
            System.out.println(GREEN + BOLD + "  ✓ 未发现问题，代码看起来不错！" + RESET);
            System.out.println();
            return;
        }

        for (ReviewIssue issue : result.getIssues()) {
            printIssue(issue);
        }

        System.out.println(BOLD + DOUBLE_LINE + RESET);

        // 总结行
        if (result.hasCriticalIssues()) {
            int critical = result.getIssuesBySeverity(Severity.CRITICAL).size();
            System.out.println(RED + BOLD + "  ✗ 发现 " + critical + " 个严重问题 - 提交已中止" + RESET);
            System.out.println(GRAY + "  使用 --force 参数可跳过审查" + RESET);
        } else {
            System.out.println(GREEN + BOLD + "  ✓ 无严重问题 - 允许提交" + RESET);
        }

        // 统计信息
        System.out.println(GRAY + String.format(
                "  耗时：%dms | Token数：%d | 文件数：%d",
                result.getReviewDurationMs(),
                result.getTotalTokensUsed(),
                result.getTotalFilesReviewed()) + RESET);
        System.out.println(BOLD + DOUBLE_LINE + RESET);
        System.out.println();
    }

    /**
     * 直接打印LLM输出的原始文本报告。
     */
    private static void printRawReport(ReviewResult result) {
        System.out.println(result.getRawReport());

        System.out.println();
        System.out.println(GRAY + String.format(
                "  耗时：%dms | Token数：%d | 文件数：%d",
                result.getReviewDurationMs(),
                result.getTotalTokensUsed(),
                result.getTotalFilesReviewed()) + RESET);

        if (result.isUncertainResult()) {
            System.out.println(YELLOW + BOLD + "  ⚠ AI 未返回结构化结果，无法自动判定是否存在严重问题。" + RESET);
            System.out.println(YELLOW + "  请人工审阅上方报告内容。如发现问题，使用 --force 可跳过审查。" + RESET);
        } else if (result.hasCriticalIssues()) {
            System.out.println(RED + BOLD + "  ✗ 发现严重问题 - 提交已中止" + RESET);
            System.out.println(GRAY + "  使用 --force 参数可跳过审查" + RESET);
        } else {
            System.out.println(GREEN + BOLD + "  ✓ 无严重问题 - 允许提交" + RESET);
        }
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

        System.out.printf("    %s类型：%s %s%n", GRAY, RESET, issue.getType());
        System.out.printf("    %s信息：%s %s%n", GRAY, RESET, issue.getMessage());

        if (issue.getSuggestion() != null && !issue.getSuggestion().isBlank()) {
            System.out.printf("    %s建议：%s %s%n", GRAY, RESET, issue.getSuggestion());
        }

        System.out.println();
    }
}
