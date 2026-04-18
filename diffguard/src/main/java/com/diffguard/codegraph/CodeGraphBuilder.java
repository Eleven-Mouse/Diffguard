package com.diffguard.codegraph;

import com.diffguard.ast.ProjectASTAnalyzer;
import com.diffguard.ast.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 从 ProjectASTAnalyzer 的分析结果构建 CodeGraph。
 * <p>
 * 构建规则：
 * - 每个文件 → FILE 节点
 * - 每个类/接口 → CLASS/INTERFACE 节点，CONTAINS 边连接到 FILE
 * - 每个方法 → METHOD 节点，CONTAINS 边连接到 CLASS
 * - 类实现接口 → IMPLEMENTS 边
 * - 类继承类 → EXTENDS 边
 * - 方法调用方法 → CALLS 边（同文件 + 跨文件）
 * - import 语句 → IMPORTS 边（FILE → FILE）
 */
public class CodeGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphBuilder.class);

    /**
     * 从项目目录构建完整的代码图谱。
     */
    public static CodeGraph buildFromProject(Path projectDir) {
        ProjectASTAnalyzer analyzer = new ProjectASTAnalyzer();
        int fileCount = analyzer.scanProject(projectDir);
        log.info("扫描 {} 个文件用于构建代码图谱", fileCount);
        return buildFromAnalyzer(analyzer);
    }

    /**
     * 从已有的 ProjectASTAnalyzer 结果构建图谱。
     */
    public static CodeGraph buildFromAnalyzer(ProjectASTAnalyzer analyzer) {
        CodeGraph graph = new CodeGraph();

        // Pass 1: 创建所有节点
        for (var entry : analyzer.getAllResults().entrySet()) {
            String filePath = entry.getKey();
            ASTAnalysisResult result = entry.getValue();
            if (!result.isParseSucceeded()) continue;

            buildFileAndClassNodes(graph, filePath, result);
        }

        // Pass 2: 创建方法节点
        for (var entry : analyzer.getAllResults().entrySet()) {
            String filePath = entry.getKey();
            ASTAnalysisResult result = entry.getValue();
            if (!result.isParseSucceeded()) continue;

            buildMethodNodes(graph, filePath, result);
        }

        // Pass 3: 创建关系边
        for (var entry : analyzer.getAllResults().entrySet()) {
            String filePath = entry.getKey();
            ASTAnalysisResult result = entry.getValue();
            if (!result.isParseSucceeded()) continue;

            buildInheritanceEdges(graph, filePath, result);
            buildCallEdges(graph, filePath, result);
            buildImportEdges(graph, filePath, result);
        }

        // Pass 4: 跨文件调用边
        buildCrossFileCallEdges(graph, analyzer);

        log.info("代码图谱构建完成：{} 节点，{} 边", graph.nodeCount(), graph.edgeCount());
        return graph;
    }

    // --- Pass 1: FILE + CLASS/INTERFACE nodes ---

    private static void buildFileAndClassNodes(CodeGraph graph, String filePath,
                                                ASTAnalysisResult result) {
        String fileId = "file:" + filePath;
        graph.addNode(new GraphNode(GraphNode.Type.FILE, fileId, extractFileName(filePath), filePath));

        for (ClassInfo cls : result.getClasses()) {
            GraphNode.Type nodeType = "interface".equals(cls.getType())
                    ? GraphNode.Type.INTERFACE
                    : GraphNode.Type.CLASS;
            String classId = "class:" + cls.getName();
            graph.addNode(new GraphNode(nodeType, classId, cls.getName(), filePath));
            graph.addEdge(new GraphEdge(GraphEdge.Type.CONTAINS, fileId, classId));
        }
    }

    // --- Pass 2: METHOD nodes ---

    private static void buildMethodNodes(CodeGraph graph, String filePath,
                                          ASTAnalysisResult result) {
        for (ClassInfo cls : result.getClasses()) {
            String classId = "class:" + cls.getName();

            for (MethodInfo method : result.getMethods()) {
                if (method.getStartLine() >= cls.getStartLine()
                        && method.getEndLine() <= cls.getEndLine()) {
                    String methodId = "method:" + cls.getName() + "." + method.getName()
                            + "(" + String.join(",", method.getParameterTypes()) + ")";
                    graph.addNode(new GraphNode(GraphNode.Type.METHOD, methodId,
                            method.getName(), filePath));
                    graph.addEdge(new GraphEdge(GraphEdge.Type.CONTAINS, classId, methodId));
                }
            }
        }
    }

    // --- Pass 3: Inheritance + Call + Import edges ---

    private static void buildInheritanceEdges(CodeGraph graph, String filePath,
                                               ASTAnalysisResult result) {
        for (ClassInfo cls : result.getClasses()) {
            String classId = "class:" + cls.getName();

            // extends
            if (cls.getSuperClass() != null && !cls.getSuperClass().isEmpty()) {
                String parentId = "class:" + cls.getSuperClass();
                if (graph.getNode(parentId).isPresent()) {
                    graph.addEdge(new GraphEdge(GraphEdge.Type.EXTENDS, classId, parentId));
                }
            }

            // implements
            for (String iface : cls.getInterfaces()) {
                String ifaceId = "class:" + iface;
                if (graph.getNode(ifaceId).isPresent()) {
                    graph.addEdge(new GraphEdge(GraphEdge.Type.IMPLEMENTS, classId, ifaceId));
                }
            }
        }
    }

    private static void buildCallEdges(CodeGraph graph, String filePath,
                                        ASTAnalysisResult result) {
        for (ResolvedCallEdge edge : result.getResolvedCallEdges()) {
            String callerClass = edge.getCallerClass();
            String callerMethod = edge.getCallerMethod();
            String calleeMethod = edge.getCalleeMethod();

            // Find caller method node
            String callerId = findMethodNodeId(graph, callerClass, callerMethod);
            if (callerId == null) continue;

            // Try to find callee method node (same class first, then other classes)
            String calleeId = findMethodNodeId(graph, callerClass, calleeMethod);
            if (calleeId == null) {
                // Search across all classes
                calleeId = findMethodNodeIdGlobally(graph, calleeMethod);
            }

            if (calleeId != null) {
                graph.addEdge(new GraphEdge(GraphEdge.Type.CALLS, callerId, calleeId,
                        edge.getLineNumber()));
            }
        }
    }

    private static void buildImportEdges(CodeGraph graph, String filePath,
                                          ASTAnalysisResult result) {
        String fileId = "file:" + filePath;

        for (String imp : result.getImports()) {
            // Resolve import to a file node
            String importedFile = resolveImportToFile(imp);
            if (importedFile != null) {
                String importedFileId = "file:" + importedFile;
                if (graph.getNode(importedFileId).isPresent()) {
                    graph.addEdge(new GraphEdge(GraphEdge.Type.IMPORTS, fileId, importedFileId));
                }
            }
        }
    }

    // --- Pass 4: Cross-file call edges ---

    private static void buildCrossFileCallEdges(CodeGraph graph, ProjectASTAnalyzer analyzer) {
        for (ProjectASTAnalyzer.CrossFileCall call : analyzer.buildCrossFileCallGraph()) {
            String callerId = findMethodNodeId(graph, call.getSourceClass(), call.getSourceMethod());
            String calleeId = findMethodNodeId(graph, call.getTargetClass(), call.getTargetMethod());

            if (callerId != null && calleeId != null) {
                graph.addEdge(new GraphEdge(GraphEdge.Type.CALLS, callerId, calleeId,
                        call.getLineNumber()));
            }
        }
    }

    // --- Helpers ---

    private static String findMethodNodeId(CodeGraph graph, String className, String methodName) {
        if (className == null || className.isEmpty() || methodName == null) return null;
        String candidateId = "method:" + className + "." + methodName + "()";
        if (graph.getNode(candidateId).isPresent()) return candidateId;

        // Try with parameters - search for partial match
        for (GraphNode node : graph.getNodesByType(GraphNode.Type.METHOD)) {
            if (node.getName().equals(methodName) && node.getId().contains(className + ".")) {
                return node.getId();
            }
        }
        return null;
    }

    private static String findMethodNodeIdGlobally(CodeGraph graph, String methodName) {
        for (GraphNode node : graph.getNodesByType(GraphNode.Type.METHOD)) {
            if (node.getName().equals(methodName)) {
                return node.getId();
            }
        }
        return null;
    }

    private static String resolveImportToFile(String importPath) {
        // Convert "com.example.Service" to a possible file path "com/example/Service.java"
        if (importPath == null || !importPath.contains(".")) return null;
        return importPath.replace('.', '/') + ".java";
    }

    private static String extractFileName(String path) {
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
