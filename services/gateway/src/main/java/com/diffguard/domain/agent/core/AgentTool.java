package com.diffguard.domain.agent.core;

/**
 * Agent 工具接口，定义统一的工具执行契约。
 * Tool Server 中的每个工具端点对应一个 AgentTool 实现。
 */
public interface AgentTool {

    /** 工具名称，与 Tool Server 端点路由对应。 */
    String name();

    /** 工具描述，供调用方参考。 */
    String description();

    /**
     * 执行工具逻辑。
     *
     * @param input   工具输入（文件路径或查询字符串）
     * @param context 当前审查会话上下文
     * @return 工具执行结果
     */
    ToolResult execute(String input, AgentContext context);
}
