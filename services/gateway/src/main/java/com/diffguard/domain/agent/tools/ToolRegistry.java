package com.diffguard.domain.agent.tools;

import com.diffguard.domain.agent.core.AgentTool;


import java.nio.file.Path;
import java.util.*;

/**
 * Agent 工具注册中心。
 * <p>
 * 管理所有可用工具的注册、发现和初始化。
 * 提供便捷的工厂方法来创建标准工具集。
 */
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry register(AgentTool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public ToolRegistry registerAll(Collection<AgentTool> toolList) {
        for (AgentTool tool : toolList) {
            tools.put(tool.name(), tool);
        }
        return this;
    }

    public Optional<AgentTool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<AgentTool> getAllTools() {
        return List.copyOf(tools.values());
    }

    public List<String> getToolNames() {
        return List.copyOf(tools.keySet());
    }

    public int size() { return tools.size(); }

    /**
     * 创建标准的代码审查工具集。
     */
    public static ToolRegistry createStandardTools(Path projectDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new GetFileContentTool(projectDir));
        registry.register(new GetDiffContextTool());
        registry.register(new GetMethodDefinitionTool(projectDir));
        return registry;
    }

    /**
     * 创建带沙箱限制的代码审查工具集。
     * <p>
     * 文件读取类工具将通过 FileAccessSandbox 限制可访问范围。
     */
    public static ToolRegistry createSandboxedTools(Path projectDir, FileAccessSandbox sandbox) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new GetFileContentTool(projectDir, sandbox));
        registry.register(new GetDiffContextTool());
        registry.register(new GetMethodDefinitionTool(projectDir, sandbox));
        return registry;
    }

    /**
     * 创建完整工具集（包含 Code Graph 和 RAG）。
     * <p>
     * 注意：Code Graph 和 RAG 的初始化较重，建议在项目级复用。
     */
    public static ToolRegistry createFullToolset(Path projectDir,
                                                  GetCallGraphTool callGraphTool,
                                                  SemanticSearchTool semanticSearchTool) {
        ToolRegistry registry = createStandardTools(projectDir);
        if (callGraphTool != null) {
            registry.register(callGraphTool);
            registry.register(new GetRelatedFilesTool(callGraphTool));
        }
        if (semanticSearchTool != null) {
            registry.register(semanticSearchTool);
        }
        return registry;
    }
}
