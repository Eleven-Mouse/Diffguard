package com.diffguard.model;

import java.util.ArrayList;
import java.util.List;

public class ReviewResult {

    private final List<ReviewIssue> issues = new ArrayList<>();
    private int totalFilesReviewed;
    private int totalTokensUsed;
    private long reviewDurationMs;

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

    public boolean hasCriticalIssues() {
        return issues.stream().anyMatch(i -> i.getSeverity().shouldBlockCommit());
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
        int critical = getIssuesBySeverity(Severity.CRITICAL).size();
        int warning = getIssuesBySeverity(Severity.WARNING).size();
        int info = getIssuesBySeverity(Severity.INFO).size();
        return String.format("%d issues found (%d critical, %d warning, %d info)",
                issues.size(), critical, warning, info);
    }
}
