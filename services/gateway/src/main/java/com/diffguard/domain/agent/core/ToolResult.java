package com.diffguard.domain.agent.core;

/**
 * 工具执行结果。
 */
public class ToolResult {

    private final boolean success;
    private final String output;
    private final String error;

    private ToolResult(boolean success, String output, String error) {
        this.success = success;
        this.output = output;
        this.error = error;
    }

    public static ToolResult ok(String output) {
        return new ToolResult(true, output, null);
    }

    public static ToolResult error(String error) {
        return new ToolResult(false, null, error);
    }

    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getError() { return error; }

    public String toDisplayString() {
        return success ? output : "Error: " + error;
    }
}
