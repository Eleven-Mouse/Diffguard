package com.diffguard.agent.core;

/**
 * Agent 推理步骤记录（Thought → Action → Observation）。
 */
public class StepRecord {

    public enum Type {
        THOUGHT, ACTION, OBSERVATION, FINAL_ANSWER
    }

    private final Type type;
    private final String content;
    private final String toolName;
    private final String toolInput;
    private final long timestamp;

    private StepRecord(Type type, String content, String toolName, String toolInput) {
        this.type = type;
        this.content = content;
        this.toolName = toolName;
        this.toolInput = toolInput;
        this.timestamp = System.currentTimeMillis();
    }

    public static StepRecord thought(String content) {
        return new StepRecord(Type.THOUGHT, content, null, null);
    }

    public static StepRecord action(String toolName, String toolInput) {
        return new StepRecord(Type.ACTION, "Action: " + toolName + "(" + toolInput + ")",
                toolName, toolInput);
    }

    public static StepRecord observation(String content) {
        return new StepRecord(Type.OBSERVATION, content, null, null);
    }

    public static StepRecord finalAnswer(String content) {
        return new StepRecord(Type.FINAL_ANSWER, content, null, null);
    }

    public Type getType() { return type; }
    public String getContent() { return content; }
    public String getToolName() { return toolName; }
    public String getToolInput() { return toolInput; }
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return "[" + type + "] " + content;
    }
}
