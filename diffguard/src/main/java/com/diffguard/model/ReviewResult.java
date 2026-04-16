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
     * 3. 原始文本报告模式 → 不自动阻断，由用户自行判断
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
        // 3. 原始文本模式：LLM 未输出有效 JSON，无法可靠判定，
        //    默认不阻断提交，避免误报阻止正常开发流程
        return false;
    }

    /**
     * 原始文本报告是否需要用户手动确认。
     * 当 LLM 输出非 JSON 格式时，系统无法自动判定是否存在严重问题，
     * 应提示用户自行审阅报告内容。
     */
    public boolean isUncertainResult() {
        return isRawReport() && issues.isEmpty();
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
