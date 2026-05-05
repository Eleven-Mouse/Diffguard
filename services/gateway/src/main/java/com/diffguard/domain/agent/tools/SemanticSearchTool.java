package com.diffguard.domain.agent.tools;

import com.diffguard.domain.agent.core.AgentContext;
import com.diffguard.domain.agent.core.AgentTool;
import com.diffguard.domain.agent.core.ToolResult;
import com.diffguard.domain.coderag.CodeRAGService;
import com.diffguard.infrastructure.config.ReviewConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * 语义代码搜索工具，基于 Code RAG。
 */
public class SemanticSearchTool implements AgentTool {

    private final CodeRAGService ragService;

    public SemanticSearchTool(Path projectDir) {
        this.ragService = new CodeRAGService();
        this.ragService.indexProject(projectDir);
    }

    public SemanticSearchTool(CodeRAGService ragService) {
        this.ragService = ragService;
    }

    /**
     * 根据配置创建语义搜索工具，自动选择 Embedding 提供者。
     */
    public SemanticSearchTool(Path projectDir, ReviewConfig config) {
        this.ragService = new CodeRAGService(config);
        this.ragService.indexProject(projectDir);
    }

    @Override
    public String name() { return "semantic_search"; }

    @Override
    public String description() {
        return "语义搜索代码。输入: 搜索关键词或描述（如 'SQL query execution' 或 'user authentication'）";
    }

    @Override
    public ToolResult execute(String input, AgentContext context) {
        String query = input.trim().strip();
        if (query.isEmpty()) {
            return ToolResult.error("搜索关键词不能为空");
        }

        if (!ragService.isIndexed()) {
            return ToolResult.error("代码索引尚未构建");
        }

        List<CodeRAGService.RAGResult> results = ragService.search(query, 5);
        if (results.isEmpty()) {
            return ToolResult.ok("未找到相关代码: " + query);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("搜索 \"").append(query).append("\" 的结果:\n\n");
        int rank = 1;
        for (CodeRAGService.RAGResult result : results) {
            var chunk = result.chunk();
            sb.append(rank++).append(". [").append(String.format("%.2f", result.score())).append("] ");
            sb.append(chunk.getGranularity()).append(": ");

            if (chunk.getClassName() != null) sb.append(chunk.getClassName());
            if (chunk.getMethodName() != null) sb.append(".").append(chunk.getMethodName());
            sb.append(" (").append(chunk.getFilePath()).append(" L").append(chunk.getStartLine())
                    .append("-L").append(chunk.getEndLine()).append(")\n");

            // Show first 5 lines of content
            String[] lines = chunk.getContent().split("\n");
            int showLines = Math.min(5, lines.length);
            for (int i = 0; i < showLines; i++) {
                sb.append("   ").append(lines[i].stripTrailing()).append("\n");
            }
            if (lines.length > 5) {
                sb.append("   ... (").append(lines.length - 5).append(" more lines)\n");
            }
            sb.append("\n");
        }

        return ToolResult.ok(sb.toString());
    }
}
