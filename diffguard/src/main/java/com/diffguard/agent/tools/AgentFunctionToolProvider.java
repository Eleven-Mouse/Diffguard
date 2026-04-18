package com.diffguard.agent.tools;

import com.diffguard.agent.core.AgentContext;
import com.diffguard.agent.core.AgentTool;
import com.diffguard.agent.core.StepRecord;
import com.diffguard.agent.core.ToolResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * LangChain4j @Tool 适配器，将现有 {@link AgentTool} 实现暴露为 Function Calling 工具。
 * <p>
 * 每个 @Tool 方法通过名称查找并分派到对应的 {@link AgentTool}，
 * 同时记录推理步骤和工具调用次数。
 * <p>
 * 生命周期：每次 {@code ReActAgent.run()} 调用创建一个新实例，
 * 以绑定到当前 {@link AgentContext}。
 */
public class AgentFunctionToolProvider {

    private static final Logger log = LoggerFactory.getLogger(AgentFunctionToolProvider.class);

    private final Map<String, AgentTool> tools;
    private final AgentContext context;
    private final List<StepRecord> trace;
    private final int maxToolCalls;
    private int callCount;

    public AgentFunctionToolProvider(List<AgentTool> tools, AgentContext context,
                                     List<StepRecord> trace) {
        this(tools, context, trace, context.getMaxToolCalls());
    }

    public AgentFunctionToolProvider(List<AgentTool> tools, AgentContext context,
                                     List<StepRecord> trace, int maxToolCalls) {
        this.tools = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            this.tools.put(tool.name(), tool);
        }
        this.context = context;
        this.trace = trace;
        this.maxToolCalls = maxToolCalls;
        this.callCount = 0;
    }

    // --- @Tool methods ---

    @Tool("Read the full content of a source file in the project. "
            + "Use this to understand the broader context around changed lines, "
            + "check class structure, field declarations, and surrounding code.")
    public String getFileContent(
            @P("Relative file path, e.g. 'src/main/java/com/example/Service.java'") String filePath) {
        return dispatch("get_file_content", filePath);
    }

    @Tool("Get diff context for the current code changes. "
            + "Returns a summary of all changed files, or the diff content for a specific file.")
    public String getDiffContext(
            @P("'summary' for overview, or a specific file path") String query) {
        return dispatch("get_diff_context", query);
    }

    @Tool("Get method signatures and class structure of a Java file. "
            + "Returns imports, classes with fields, method signatures with visibility and annotations, "
            + "and method call edges.")
    public String getMethodDefinition(
            @P("Relative path to a Java file") String filePath) {
        return dispatch("get_method_definition", filePath);
    }

    @Tool("Query the code call graph to understand dependencies. "
            + "Use 'callers <method>' to find who calls a method, "
            + "'callees <method>' to find what a method calls, "
            + "or 'impact <Class.method>' for change impact analysis.")
    public String getCallGraph(
            @P("Query: 'callers <method>', 'callees <method>', or 'impact <Class.method>'") String query) {
        return dispatch("get_call_graph", query);
    }

    @Tool("Find files related to a given file or class, including dependencies, "
            + "dependents, inheritance hierarchy, implementations, and subclasses.")
    public String getRelatedFiles(
            @P("File path or class name") String query) {
        return dispatch("get_related_files", query);
    }

    @Tool("Semantic code search powered by Code RAG. "
            + "Find relevant code by describing what you're looking for, e.g. 'SQL query execution' or 'user authentication'.")
    public String semanticSearch(
            @P("Search query describing the code you want to find") String query) {
        return dispatch("semantic_search", query);
    }

    // --- Dispatch ---

    private String dispatch(String toolName, String input) {
        if (callCount >= maxToolCalls) {
            return "已达到工具调用上限（" + maxToolCalls + "次），请基于已有信息完成审查。";
        }

        trace.add(StepRecord.action(toolName, input));
        context.recordStep(StepRecord.action(toolName, input));

        AgentTool tool = tools.get(toolName);
        ToolResult result;
        if (tool != null) {
            try {
                result = tool.execute(input, context);
            } catch (Exception e) {
                log.debug("工具执行失败 {}({}): {}", toolName, input, e.getMessage());
                result = ToolResult.error("工具执行失败: " + e.getMessage());
            }
        } else {
            result = ToolResult.error("未知工具: " + toolName + "，可用工具: " + String.join(", ", tools.keySet()));
        }

        callCount++;
        context.incrementToolCalls();

        String observation = result.toDisplayString();
        if (observation.length() > 3000) {
            observation = observation.substring(0, 3000) + "\n... (输出已截断)";
        }

        trace.add(StepRecord.observation(observation));
        context.recordStep(StepRecord.observation(observation));

        return observation;
    }

    public int getCallCount() {
        return callCount;
    }
}
