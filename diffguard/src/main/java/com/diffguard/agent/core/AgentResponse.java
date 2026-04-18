package com.diffguard.agent.core;

import com.diffguard.model.ReviewIssue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent 最终响应。
 */
public class AgentResponse {

    private final boolean hasCritical;
    private final String summary;
    private final List<ReviewIssue> issues;
    private final List<String> highlights;
    private final List<String> testSuggestions;
    private final List<StepRecord> reasoningTrace;
    private final int totalTokensUsed;
    private final int toolCallsMade;
    private final boolean completed;
    private final String rawResponse;

    private AgentResponse(Builder builder) {
        this.hasCritical = builder.hasCritical;
        this.summary = builder.summary;
        this.issues = Collections.unmodifiableList(new ArrayList<>(builder.issues));
        this.highlights = Collections.unmodifiableList(new ArrayList<>(builder.highlights));
        this.testSuggestions = Collections.unmodifiableList(new ArrayList<>(builder.testSuggestions));
        this.reasoningTrace = builder.reasoningTrace != null
                ? Collections.unmodifiableList(new ArrayList<>(builder.reasoningTrace))
                : List.of();
        this.totalTokensUsed = builder.totalTokensUsed;
        this.toolCallsMade = builder.toolCallsMade;
        this.completed = builder.completed;
        this.rawResponse = builder.rawResponse;
    }

    public boolean isHasCritical() { return hasCritical; }
    public String getSummary() { return summary; }
    public List<ReviewIssue> getIssues() { return issues; }
    public List<String> getHighlights() { return highlights; }
    public List<String> getTestSuggestions() { return testSuggestions; }
    public List<StepRecord> getReasoningTrace() { return reasoningTrace; }
    public int getTotalTokensUsed() { return totalTokensUsed; }
    public int getToolCallsMade() { return toolCallsMade; }
    public boolean isCompleted() { return completed; }
    public String getRawResponse() { return rawResponse; }

    /**
     * 获取推理过程的可读摘要。
     */
    public String getReasoningSummary() {
        StringBuilder sb = new StringBuilder();
        for (StepRecord step : reasoningTrace) {
            sb.append(step).append("\n");
        }
        return sb.toString();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private boolean hasCritical;
        private String summary;
        private final List<ReviewIssue> issues = new ArrayList<>();
        private final List<String> highlights = new ArrayList<>();
        private final List<String> testSuggestions = new ArrayList<>();
        private List<StepRecord> reasoningTrace;
        private int totalTokensUsed;
        private int toolCallsMade;
        private boolean completed = true;
        private String rawResponse;

        public Builder hasCritical(boolean v) { hasCritical = v; return this; }
        public Builder summary(String v) { summary = v; return this; }
        public Builder issue(ReviewIssue v) { issues.add(v); return this; }
        public Builder highlight(String v) { highlights.add(v); return this; }
        public Builder testSuggestion(String v) { testSuggestions.add(v); return this; }
        public Builder reasoningTrace(List<StepRecord> v) { reasoningTrace = v; return this; }
        public Builder totalTokensUsed(int v) { totalTokensUsed = v; return this; }
        public Builder toolCallsMade(int v) { toolCallsMade = v; return this; }
        public Builder completed(boolean v) { completed = v; return this; }
        public Builder rawResponse(String v) { rawResponse = v; return this; }
        public AgentResponse build() { return new AgentResponse(this); }
    }
}
