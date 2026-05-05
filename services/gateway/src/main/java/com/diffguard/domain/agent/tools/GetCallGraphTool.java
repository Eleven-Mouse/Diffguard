package com.diffguard.domain.agent.tools;

import com.diffguard.domain.agent.core.AgentContext;
import com.diffguard.domain.agent.core.AgentTool;
import com.diffguard.domain.agent.core.ToolResult;
import com.diffguard.domain.codegraph.CodeGraph;
import com.diffguard.domain.codegraph.CodeGraphBuilder;
import com.diffguard.domain.codegraph.GraphNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 查询代码调用图关系。
 */
public class GetCallGraphTool implements AgentTool {

    private final CodeGraph codeGraph;

    public GetCallGraphTool(Path projectDir) {
        this.codeGraph = CodeGraphBuilder.buildFromProject(projectDir);
    }

    public GetCallGraphTool(CodeGraph codeGraph) {
        this.codeGraph = codeGraph;
    }

    public CodeGraph getCodeGraph() { return codeGraph; }

    @Override
    public String name() { return "get_call_graph"; }

    @Override
    public String description() {
        return "查询代码调用关系。输入格式: 'callers 方法名' 或 'callees 方法名' 或 'impact 类名.方法名'";
    }

    @Override
    public ToolResult execute(String input, AgentContext context) {
        String query = input.trim().strip();
        if (query.startsWith("callers ")) {
            return findCallers(query.substring(8).strip());
        }
        if (query.startsWith("callees ")) {
            return findCallees(query.substring(8).strip());
        }
        if (query.startsWith("impact ")) {
            return computeImpact(query.substring(7).strip());
        }
        return ToolResult.error("未知查询格式。使用: 'callers 方法名', 'callees 方法名', 或 'impact 类名.方法名'");
    }

    private ToolResult findCallers(String methodName) {
        List<GraphNode> methodNodes = codeGraph.getNodesByType(GraphNode.Type.METHOD).stream()
                .filter(n -> n.getName().equals(methodName))
                .toList();

        if (methodNodes.isEmpty()) {
            return ToolResult.error("未找到方法: " + methodName);
        }

        StringBuilder sb = new StringBuilder();
        for (GraphNode node : methodNodes) {
            List<GraphNode> callers = codeGraph.getCallersOf(node.getId());
            sb.append("方法 ").append(node.getId()).append(" 的调用方:\n");
            for (GraphNode caller : callers) {
                sb.append("  - ").append(caller.getName())
                        .append(" (").append(caller.getFilePath()).append(")\n");
            }
            if (callers.isEmpty()) {
                sb.append("  (无外部调用方)\n");
            }
        }
        return ToolResult.ok(sb.toString());
    }

    private ToolResult findCallees(String methodName) {
        List<GraphNode> methodNodes = codeGraph.getNodesByType(GraphNode.Type.METHOD).stream()
                .filter(n -> n.getName().equals(methodName))
                .toList();

        if (methodNodes.isEmpty()) {
            return ToolResult.error("未找到方法: " + methodName);
        }

        StringBuilder sb = new StringBuilder();
        for (GraphNode node : methodNodes) {
            List<GraphNode> callees = codeGraph.getCalleesOf(node.getId());
            sb.append("方法 ").append(node.getId()).append(" 调用了:\n");
            for (GraphNode callee : callees) {
                sb.append("  - ").append(callee.getName())
                        .append(" (").append(callee.getFilePath()).append(")\n");
            }
        }
        return ToolResult.ok(sb.toString());
    }

    private ToolResult computeImpact(String target) {
        // Try to find a method node matching "ClassName.methodName"
        String[] parts = target.split("\\.");
        Set<String> targetIds;
        if (parts.length == 2) {
            String prefix = "method:" + parts[0] + "." + parts[1];
            targetIds = codeGraph.getAllNodes().stream()
                    .filter(n -> n.getType() == GraphNode.Type.METHOD && n.getId().startsWith(prefix))
                    .map(GraphNode::getId)
                    .collect(Collectors.toSet());
        } else {
            targetIds = codeGraph.getNodesByType(GraphNode.Type.METHOD).stream()
                    .filter(n -> n.getName().equals(target))
                    .map(GraphNode::getId)
                    .collect(Collectors.toSet());
        }

        if (targetIds.isEmpty()) {
            return ToolResult.error("未找到: " + target);
        }

        Set<GraphNode> impacted = codeGraph.computeImpactSet(targetIds, 3);
        StringBuilder sb = new StringBuilder();
        sb.append("变更影响范围 (").append(target).append("):\n");
        for (GraphNode node : impacted) {
            sb.append("  - ").append(node.getType()).append(": ").append(node.getName());
            if (node.getFilePath() != null) {
                sb.append(" (").append(node.getFilePath()).append(")");
            }
            sb.append("\n");
        }
        if (impacted.isEmpty()) {
            sb.append("  (无外部影响)\n");
        }
        return ToolResult.ok(sb.toString());
    }
}
