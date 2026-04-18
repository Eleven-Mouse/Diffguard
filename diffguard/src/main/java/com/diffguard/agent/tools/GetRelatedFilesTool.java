package com.diffguard.agent.tools;

import com.diffguard.agent.core.AgentContext;
import com.diffguard.agent.core.AgentTool;
import com.diffguard.agent.core.ToolResult;
import com.diffguard.codegraph.CodeGraph;
import com.diffguard.codegraph.GraphNode;

import java.util.List;
import java.util.Set;

/**
 * 查找与指定文件/类相关的文件。
 */
public class GetRelatedFilesTool implements AgentTool {

    private final GetCallGraphTool callGraphTool;

    public GetRelatedFilesTool(GetCallGraphTool callGraphTool) {
        this.callGraphTool = callGraphTool;
    }

    @Override
    public String name() { return "get_related_files"; }

    @Override
    public String description() {
        return "查找与指定文件或类相关的文件。输入: 文件路径或类名";
    }

    @Override
    public ToolResult execute(String input, AgentContext context) {
        String query = input.trim().strip();
        CodeGraph graph = callGraphTool.getCodeGraph();

        // Try as file path
        String fileId = "file:" + query;
        if (graph.getNode(fileId).isPresent()) {
            return findRelatedToFile(graph, fileId, query);
        }

        // Try as class name
        String classId = "class:" + query;
        if (graph.getNode(classId).isPresent()) {
            return findRelatedToClass(graph, classId, query);
        }

        return ToolResult.error("未找到: " + query);
    }

    private ToolResult findRelatedToFile(CodeGraph graph, String fileId, String fileName) {
        Set<GraphNode> dependents = graph.getDependents(fileId);
        Set<GraphNode> dependencies = graph.getDependencies(fileId);

        StringBuilder sb = new StringBuilder();
        sb.append("与 ").append(fileName).append(" 相关的文件:\n");

        if (!dependencies.isEmpty()) {
            sb.append("\n依赖的文件:\n");
            for (GraphNode dep : dependencies) {
                sb.append("  - ").append(dep.getName());
                if (dep.getFilePath() != null) sb.append(" (").append(dep.getFilePath()).append(")");
                sb.append("\n");
            }
        }

        if (!dependents.isEmpty()) {
            sb.append("\n被依赖的文件:\n");
            for (GraphNode dep : dependents) {
                sb.append("  - ").append(dep.getName());
                if (dep.getFilePath() != null) sb.append(" (").append(dep.getFilePath()).append(")");
                sb.append("\n");
            }
        }

        // Also check classes in this file
        List<GraphNode> classes = graph.getClassesInFile(fileId);
        for (GraphNode cls : classes) {
            List<GraphNode> impls = graph.getImplementationsOf(cls.getId());
            if (!impls.isEmpty()) {
                sb.append("\n").append(cls.getName()).append(" 的实现:\n");
                for (GraphNode impl : impls) {
                    sb.append("  - ").append(impl.getName()).append("\n");
                }
            }
            graph.getParentClass(cls.getId()).ifPresent(parent ->
                    sb.append("\n").append(cls.getName()).append(" 继承 ").append(parent.getName()).append("\n"));
        }

        return ToolResult.ok(sb.toString());
    }

    private ToolResult findRelatedToClass(CodeGraph graph, String classId, String className) {
        StringBuilder sb = new StringBuilder();
        sb.append("与 ").append(className).append(" 相关:\n");

        // Methods
        List<GraphNode> methods = graph.getMethodsInClass(classId);
        if (!methods.isEmpty()) {
            sb.append("\n方法:\n");
            for (GraphNode m : methods) {
                sb.append("  - ").append(m.getName()).append("\n");
            }
        }

        // Parent
        graph.getParentClass(classId).ifPresent(parent ->
                sb.append("\n继承: ").append(parent.getName()).append("\n"));

        // Subclasses
        List<GraphNode> subs = graph.getSubClasses(classId);
        if (!subs.isEmpty()) {
            sb.append("\n子类:\n");
            for (GraphNode sub : subs) {
                sb.append("  - ").append(sub.getName()).append("\n");
            }
        }

        // Implementations
        List<GraphNode> impls = graph.getImplementationsOf(classId);
        if (!impls.isEmpty()) {
            sb.append("\n实现类:\n");
            for (GraphNode impl : impls) {
                sb.append("  - ").append(impl.getName()).append("\n");
            }
        }

        return ToolResult.ok(sb.toString());
    }
}
