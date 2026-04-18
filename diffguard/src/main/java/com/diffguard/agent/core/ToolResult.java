package com.diffguard.agent.core;

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

    /**
     * 获取用于展示给 LLM 的文本。
     */
    public String toDisplayString() {
        if (success) {
            return output != null ? output : "(empty result)";
        }
        return "Error: " + error;
    }
}
