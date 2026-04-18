package com.diffguard.agent.core;

import com.diffguard.model.DiffFileEntry;

import java.nio.file.Path;
import java.util.*;

/**
 * Agent 运行上下文，持有 review session 的所有状态。
 */
public class AgentContext {

    private final Path projectDir;
    private final List<DiffFileEntry> diffEntries;
    private final Map<String, Object> attributes;
    private final List<StepRecord> stepHistory;
    private int totalTokensUsed;
    private int toolCallCount;
    private final int maxToolCalls;

    public AgentContext(Path projectDir, List<DiffFileEntry> diffEntries) {
        this(projectDir, diffEntries, 20);
    }

    public AgentContext(Path projectDir, List<DiffFileEntry> diffEntries, int maxToolCalls) {
        this.projectDir = projectDir;
        this.diffEntries = List.copyOf(diffEntries);
        this.attributes = new HashMap<>();
        this.stepHistory = new ArrayList<>();
        this.totalTokensUsed = 0;
        this.toolCallCount = 0;
        this.maxToolCalls = maxToolCalls;
    }

    public Path getProjectDir() { return projectDir; }
    public List<DiffFileEntry> getDiffEntries() { return diffEntries; }
    public int getTotalTokensUsed() { return totalTokensUsed; }
    public int getToolCallCount() { return toolCallCount; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public List<StepRecord> getStepHistory() { return Collections.unmodifiableList(stepHistory); }

    public void addTokens(int tokens) { totalTokensUsed += tokens; }
    public void incrementToolCalls() { toolCallCount++; }
    public boolean isToolCallLimitReached() { return toolCallCount >= maxToolCalls; }

    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }

    public void recordStep(StepRecord step) { stepHistory.add(step); }

    /**
     * 获取所有 diff 文件路径。
     */
    public List<String> getDiffFilePaths() {
        return diffEntries.stream().map(DiffFileEntry::getFilePath).toList();
    }

    /**
     * 获取指定文件的 diff 内容。
     */
    public Optional<String> getDiffContent(String filePath) {
        return diffEntries.stream()
                .filter(e -> e.getFilePath().equals(filePath))
                .map(DiffFileEntry::getContent)
                .findFirst();
    }

    /**
     * 获取合并后的完整 diff 内容。
     */
    public String getCombinedDiff() {
        return String.join("\n\n", diffEntries.stream()
                .map(e -> "--- " + e.getFilePath() + " ---\n" + e.getContent())
                .toList());
    }
}
