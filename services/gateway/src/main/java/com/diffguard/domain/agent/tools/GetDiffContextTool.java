package com.diffguard.domain.agent.tools;

import com.diffguard.domain.agent.core.AgentContext;
import com.diffguard.domain.agent.core.AgentTool;
import com.diffguard.domain.agent.core.ToolResult;

/**
 * 获取当前 diff 的上下文信息。
 */
public class GetDiffContextTool implements AgentTool {

    @Override
    public String name() { return "get_diff_context"; }

    @Override
    public String description() {
        return "获取当前代码变更的上下文信息。输入: 'summary'（变更摘要）或文件路径（指定文件的 diff）";
    }

    @Override
    public ToolResult execute(String input, AgentContext context) {
        String query = input.trim();

        if ("summary".equalsIgnoreCase(query)) {
            return ToolResult.ok(buildDiffSummary(context));
        }

        // 尝试作为文件路径查询（保留原始大小写）
        String content = context.getDiffContent(query);
        if (content != null && !content.isEmpty()) {
            return ToolResult.ok("文件 " + query + " 的变更:\n" + content);
        }

        // 列出所有变更文件
        return ToolResult.ok(buildDiffSummary(context));
    }

    private String buildDiffSummary(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前代码变更包含 ").append(context.getDiffEntries().size()).append(" 个文件:\n");
        for (var entry : context.getDiffEntries()) {
            sb.append("- ").append(entry.getFilePath())
                    .append(" (").append(entry.getLineCount()).append(" 行, ")
                    .append(entry.getTokenCount()).append(" tokens)\n");
        }
        sb.append("\n使用 get_diff_context(\"文件路径\") 查看特定文件的 diff 内容。");
        return sb.toString();
    }
}
