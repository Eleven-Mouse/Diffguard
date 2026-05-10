package com.diffguard.domain.agent.core;

import com.diffguard.adapter.toolserver.model.DiffFileEntry;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 运行上下文，持有单次审查会话的状态。
 * Tool Server 为每个会话创建一个 AgentContext。
 */
public class AgentContext {

    private final Path projectDir;
    private final List<DiffFileEntry> diffEntries;
    private final AtomicInteger totalTokensUsed = new AtomicInteger(0);
    private final AtomicInteger toolCallCount = new AtomicInteger(0);

    public AgentContext(Path projectDir, List<DiffFileEntry> diffEntries) {
        this.projectDir = projectDir;
        this.diffEntries = List.copyOf(diffEntries);
    }

    public Path getProjectDir() { return projectDir; }
    public List<DiffFileEntry> getDiffEntries() { return diffEntries; }
    public int getTotalTokensUsed() { return totalTokensUsed.get(); }
    public void addTokens(int tokens) { totalTokensUsed.addAndGet(tokens); }

    public List<String> getDiffFilePaths() {
        return diffEntries.stream().map(DiffFileEntry::getFilePath).toList();
    }

    public String getDiffContent(String filePath) {
        return diffEntries.stream()
                .filter(e -> filePath.equals(e.getFilePath()))
                .map(DiffFileEntry::getContent)
                .findFirst()
                .orElse("");
    }

    public String getCombinedDiff() {
        StringBuilder sb = new StringBuilder();
        for (DiffFileEntry entry : diffEntries) {
            sb.append("--- ").append(entry.getFilePath()).append("\n");
            sb.append(entry.getContent()).append("\n\n");
        }
        return sb.toString();
    }
}
