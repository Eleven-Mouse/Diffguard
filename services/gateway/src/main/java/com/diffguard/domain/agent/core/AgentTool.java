package com.diffguard.domain.agent.core;

import java.util.Map;

/**
 * Agent 工具接口，定义统一的工具执行契约。
 * <p>
 * Tool Server 中的每个工具端点对应一个 AgentTool 实现。
 * 支持简单字符串输入和结构化参数输入两种模式。
 */
public interface AgentTool {

    /** 工具名称，与 Tool Server 端点路由对应。 */
    String name();

    /** 工具描述，供调用方参考。 */
    String description();

    /**
     * 工具类别，用于分类展示和权限控制。
     * 默认类别为 "general"。
     */
    default String category() {
        return "general";
    }

    /**
     * 是否为只读工具（不修改任何文件或状态）。
     * 默认所有工具都是只读的。
     */
    default boolean isReadOnly() {
        return true;
    }

    /**
     * 执行工具逻辑（简单字符串输入）。
     *
     * @param input   工具输入（文件路径或查询字符串）
     * @param context 当前审查会话上下文
     * @return 工具执行结果
     */
    ToolResult execute(String input, AgentContext context);

    /**
     * 执行工具逻辑（结构化参数输入）。
     * <p>
     * 默认实现从 parameters 中提取第一个值作为 input 调用简单模式。
     * 子类可覆盖此方法以支持更复杂的参数处理。
     *
     * @param parameters 结构化参数映射
     * @param context    当前审查会话上下文
     * @return 工具执行结果
     */
    default ToolResult execute(Map<String, String> parameters, AgentContext context) {
        String input = parameters.values().stream()
                .findFirst()
                .orElse("");
        return execute(input, context);
    }
}
