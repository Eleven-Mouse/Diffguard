package com.diffguard.agent.strategy;

import java.util.*;

/**
 * Diff 变更画像：对 diff 内容的结构化分析结果。
 * <p>
 * 用于指导 Review Strategy 的选择：文件类型分布、变更规模、风险指标等。
 */
public class DiffProfile {

    public enum FileCategory {
        CONTROLLER, SERVICE, DAO, MODEL, CONFIG, TEST, UTILITY, OTHER
    }

    private final int totalFiles;
    private final int totalLines;
    private final int totalTokens;
    private final Map<FileCategory, Integer> categoryDistribution;
    private final List<String> filePaths;
    private final List<String> classNames;
    private final List<String> changedOperations;
    private final boolean hasDatabaseOperations;
    private final boolean hasSecuritySensitiveCode;
    private final boolean hasConcurrencyCode;
    private final boolean hasExternalApiCalls;
    private final RiskLevel overallRisk;

    private DiffProfile(Builder builder) {
        this.totalFiles = builder.totalFiles;
        this.totalLines = builder.totalLines;
        this.totalTokens = builder.totalTokens;
        this.categoryDistribution = Collections.unmodifiableMap(new LinkedHashMap<>(builder.categoryDistribution));
        this.filePaths = List.copyOf(builder.filePaths);
        this.classNames = List.copyOf(builder.classNames);
        this.changedOperations = List.copyOf(builder.changedOperations);
        this.hasDatabaseOperations = builder.hasDatabaseOperations;
        this.hasSecuritySensitiveCode = builder.hasSecuritySensitiveCode;
        this.hasConcurrencyCode = builder.hasConcurrencyCode;
        this.hasExternalApiCalls = builder.hasExternalApiCalls;
        this.overallRisk = builder.overallRisk;
    }

    public int getTotalFiles() { return totalFiles; }
    public boolean hasDatabaseOperations() { return hasDatabaseOperations; }
    public boolean hasSecuritySensitiveCode() { return hasSecuritySensitiveCode; }
    public boolean hasConcurrencyCode() { return hasConcurrencyCode; }
    public boolean hasExternalApiCalls() { return hasExternalApiCalls; }
    public RiskLevel getOverallRisk() { return overallRisk; }

    /**
     * 获取变更中占主导的文件类别。
     */
    public FileCategory getPrimaryCategory() {
        return categoryDistribution.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(FileCategory.OTHER);
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int totalFiles;
        private int totalLines;
        private int totalTokens;
        private final Map<FileCategory, Integer> categoryDistribution = new LinkedHashMap<>();
        private final List<String> filePaths = new ArrayList<>();
        private final List<String> classNames = new ArrayList<>();
        private final List<String> changedOperations = new ArrayList<>();
        private boolean hasDatabaseOperations;
        private boolean hasSecuritySensitiveCode;
        private boolean hasConcurrencyCode;
        private boolean hasExternalApiCalls;
        private RiskLevel overallRisk = RiskLevel.LOW;

        public Builder totalFiles(int v) { totalFiles = v; return this; }
        public Builder totalLines(int v) { totalLines = v; return this; }
        public Builder totalTokens(int v) { totalTokens = v; return this; }
        public void addCategory(FileCategory cat, int count) {
            categoryDistribution.merge(cat, count, Integer::sum);
        }
        public Builder filePath(String v) { filePaths.add(v); return this; }
        public Builder className(String v) { classNames.add(v); return this; }
        public void changedOperation(String v) { changedOperations.add(v);
        }
        public Builder hasDatabaseOperations(boolean v) { hasDatabaseOperations = v; return this; }
        public Builder hasSecuritySensitiveCode(boolean v) { hasSecuritySensitiveCode = v; return this; }
        public Builder hasConcurrencyCode(boolean v) { hasConcurrencyCode = v; return this; }
        public Builder hasExternalApiCalls(boolean v) { hasExternalApiCalls = v; return this; }
        public void overallRisk(RiskLevel v) { overallRisk = v;
        }
        public DiffProfile build() { return new DiffProfile(this); }
    }
}
