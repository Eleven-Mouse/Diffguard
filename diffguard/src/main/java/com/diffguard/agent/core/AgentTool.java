package com.diffguard.agent.core;

/**
 * Agent 可调用的工具接口。
 * <p>
 * 每个 Tool 有唯一的名称和描述，Agent 通过名称调用并获取结果。
 */
public interface AgentTool {

    /**
     * 工具名称（Agent 用此名称调用工具）。
     */
    String name();

    /**
     * 工具描述（帮助 Agent 决定何时调用此工具）。
     */
    String description();

    /**
     * 执行工具。
     *
     * @param input 工具输入参数
     * @param context Agent 上下文
     * @return 工具执行结果
     */
    ToolResult execute(String input, AgentContext context);
}
