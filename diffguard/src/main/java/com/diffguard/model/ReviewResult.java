package com.diffguard.model;

import java.util.ArrayList;
import java.util.List;

public class ReviewResult {

    private final List<ReviewIssue> issues = new ArrayList<>();
    private int totalFilesReviewed;
    private int totalTokensUsed;
    private long reviewDurationMs;

    /** LLM的原始文本报告（当输出非JSON时使用） */
    private String rawReport;

    /** LLM JSON 响应中显式标记是否存在严重问题，null 表示未从 JSON 中获取 */
    private Boolean hasCriticalFlag = null;

    public void addIssue(ReviewIssue issue) {
        issues.add(issue);
    }

    public List<ReviewIssue> getIssues() {
        return issues;
    }

    public List<ReviewIssue> getIssuesBySeverity(Severity severity) {
        return issues.stream()
                .filter(i -> i.getSeverity() == severity)
                .toList();
    }

    public String getRawReport() {
        return rawReport;
    }

    public void setRawReport(String rawReport) {
        this.rawReport = rawReport;
    }

    public void setHasCriticalFlag(boolean flag) {
        this.hasCriticalFlag = flag;
    }

    public Boolean getHasCriticalFlag() {
        return hasCriticalFlag;
    }

    /**
     * 检查结果是否为原始文本报告模式（非JSON的LLM输出）。
     */
    public boolean isRawReport() {
        return rawReport != null && !rawReport.isBlank();
    }

    /**
     * 检查是否存在应阻止提交的严重问题。
     *
     * 判定优先级：
     * 1. JSON 响应中 has_critical: true 的显式标记
     * 2. 结构化 issues 中存在 CRITICAL 级别
     * 3. 原始文本报告的启发式解析（不可靠，仅作为 fallback）
     */
    public boolean hasCriticalIssues() {
        // 1. JSON 响应中的显式标记（最可靠）
        if (Boolean.TRUE.equals(hasCriticalFlag)) {
            return true;
        }
        // 2. 结构化 issues 的严重级别
        if (!issues.isEmpty()) {
            return issues.stream().anyMatch(i -> i.getSeverity().shouldBlockCommit());
        }
        // 3. 原始文本 fallback（不可靠，仅当无结构化数据时使用）
        if (isRawReport()) {
            return hasCriticalIssuesInRawReport();
        }
        return false;
    }

    /**
     * 通过检查严重问题部分是否包含编号的问题条目
     * 而非"未发现严重问题"占位符，来检测原始文本报告中的严重问题。
     * 支持多种中文标题变体，以应对 LLM 输出的不确定性。
     */
    private boolean hasCriticalIssuesInRawReport() {
        if (rawReport == null) return false;

        // 尝试多种可能的关键词变体
        String[] startMarkers = {"严重问题", "严重的问题", "Critical Issues", "Critical"};
        String[] endMarkers = {"建议", "测试建议", "历史问题", "亮点", "总体评价", "Suggestions", "Summary"};

        String criticalSection = null;
        for (String start : startMarkers) {
            for (String end : endMarkers) {
                criticalSection = extractSection(rawReport, start, end);
                if (criticalSection != null) break;
            }
            if (criticalSection != null) break;
        }
        if (criticalSection == null) return false;

        // "未发现严重问题"类占位符变体
        String[] noIssuePhrases = {"未发现严重问题", "未发现严重", "无严重问题", "没有严重问题", "No critical issues"};
        for (String phrase : noIssuePhrases) {
            if (criticalSection.contains(phrase)) {
                return false;
            }
        }

        // 检测编号问题条目：如 "1. [ISSUE]"、"1. [文件:行号]" 等
        return criticalSection.contains("[ISSUE]")
                || criticalSection.matches("(?s).*\\d+\\.\\s+\\[.*:.*\\].*")
                || criticalSection.matches("(?s).*\\d+\\.\\s+\\S+.*");
    }

    /**
     * 提取原始报告中两个章节标题之间的内容。
     */
    private String extractSection(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        if (start < 0) return null;
        start = text.indexOf('\n', start);
        if (start < 0) return null;

        int end = text.indexOf(endMarker, start);
        if (end < 0) return text.substring(start);

        return text.substring(start, end);
    }

    public int getTotalFilesReviewed() {
        return totalFilesReviewed;
    }

    public void setTotalFilesReviewed(int totalFilesReviewed) {
        this.totalFilesReviewed = totalFilesReviewed;
    }

    public int getTotalTokensUsed() {
        return totalTokensUsed;
    }

    public void setTotalTokensUsed(int totalTokensUsed) {
        this.totalTokensUsed = totalTokensUsed;
    }

    public long getReviewDurationMs() {
        return reviewDurationMs;
    }

    public void setReviewDurationMs(long reviewDurationMs) {
        this.reviewDurationMs = reviewDurationMs;
    }

    public String getSummary() {
        if (isRawReport()) {
            return "AI 代码审查";
        }
        int critical = getIssuesBySeverity(Severity.CRITICAL).size();
        int warning = getIssuesBySeverity(Severity.WARNING).size();
        int info = getIssuesBySeverity(Severity.INFO).size();
        return String.format("发现 %d 个问题（%d 个严重，%d 个警告，%d 个提示）",
                issues.size(), critical, warning, info);
    }
}
