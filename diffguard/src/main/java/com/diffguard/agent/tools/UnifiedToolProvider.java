package com.diffguard.agent.tools;

import com.diffguard.agent.core.AgentContext;
import com.diffguard.agent.core.AgentTool;
import com.diffguard.agent.core.ToolResult;
import com.diffguard.model.DiffFileEntry;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一工具提供者，通过 LangChain4j @Tool 注解暴露给 LLM，
 * 内部委托给 {@link AgentTool} 实现。
 */
public class UnifiedToolProvider {

    private static final Logger log = LoggerFactory.getLogger(UnifiedToolProvider.class);

    private final ToolRegistry registry;
    private final AgentContext context;
    private final AtomicInteger callCount = new AtomicInteger(0);
    private final int maxToolCalls;

    public UnifiedToolProvider(Path projectDir, List<DiffFileEntry> diffEntries,
                               FileAccessSandbox sandbox, int maxToolCalls) {
        this.registry = sandbox != null
                ? ToolRegistry.createSandboxedTools(projectDir, sandbox)
                : ToolRegistry.createStandardTools(projectDir);
        this.context = new AgentContext(projectDir, diffEntries);
        this.maxToolCalls = maxToolCalls;
    }

    public void resetCallCount() {
        callCount.set(0);
    }

    @Tool("Read the full content of a file being reviewed, not just the diff. "
          + "Use this to understand the broader context around changed lines, "
          + "check class structure, field declarations, and surrounding code.")
    public String readFile(@P("Relative file path, e.g. 'src/main/java/com/example/Service.java'") String filePath) {
        return dispatch("get_file_content", filePath);
    }

    @Tool("List all method signatures in a Java file. "
          + "Returns method names, return types, and parameter types. "
          + "Use this to understand the class structure and method contracts.")
    public String listMethods(@P("Relative file path") String filePath) {
        return dispatch("get_method_definition", filePath);
    }

    @Tool("Check import statements and file structure in a Java file. "
          + "Use this to detect missing imports for newly added code.")
    public String checkImports(@P("Relative file path") String filePath) {
        return dispatch("get_file_content", filePath);
    }

    @Tool("Get the diff context for the current code changes. "
          + "Returns a summary of all changed files, or the diff for a specific file.")
    public String getDiffContext(@P("'summary' for overall summary, or a file path for specific file diff") String query) {
        return dispatch("get_diff_context", query);
    }

    private String dispatch(String toolName, String input) {
        String limitMsg = checkCallLimit();
        if (limitMsg != null) return limitMsg;

        return registry.getTool(toolName)
                .map(tool -> {
                    ToolResult result = tool.execute(input, context);
                    log.debug("Tool {} 执行：{}", toolName, result.isSuccess() ? "成功" : result.getError());
                    return result.toDisplayString();
                })
                .orElse("Tool not found: " + toolName);
    }

    private String checkCallLimit() {
        if (callCount.incrementAndGet() > maxToolCalls) {
            log.warn("Tool 调用次数已达上限（{}次），阻止后续调用", maxToolCalls);
            return "Tool 调用次数已达上限（" + maxToolCalls + "次），请基于已有信息完成审查。";
        }
        return null;
    }
}
