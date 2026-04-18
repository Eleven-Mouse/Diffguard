package com.diffguard.agent.tools;

import com.diffguard.agent.core.AgentContext;
import com.diffguard.agent.core.AgentTool;
import com.diffguard.agent.core.ToolResult;

import com.diffguard.ast.ASTAnalyzer;
import com.diffguard.ast.model.ASTAnalysisResult;
import com.diffguard.ast.model.ClassInfo;
import com.diffguard.ast.model.MethodInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 获取指定文件中的方法签名和类结构。
 */
public class GetMethodDefinitionTool implements AgentTool {

    private final Path projectDir;
    private final FileAccessSandbox sandbox;
    private final ASTAnalyzer analyzer = new ASTAnalyzer();

    public GetMethodDefinitionTool(Path projectDir) {
        this(projectDir, null);
    }

    public GetMethodDefinitionTool(Path projectDir, FileAccessSandbox sandbox) {
        this.projectDir = projectDir;
        this.sandbox = sandbox;
    }

    @Override
    public String name() { return "get_method_definition"; }

    @Override
    public String description() {
        return "获取指定 Java 文件的方法签名和类结构。输入: 文件相对路径";
    }

    @Override
    public ToolResult execute(String input, AgentContext context) {
        String filePath = input.trim().strip();
        if (filePath.isEmpty()) {
            return ToolResult.error("文件路径不能为空");
        }

        if (!filePath.endsWith(".java")) {
            return ToolResult.error("仅支持 Java 文件");
        }

        try {
            Path fullPath = projectDir.resolve(filePath).normalize();
            if (!fullPath.startsWith(projectDir)) {
                return ToolResult.error("路径超出项目范围");
            }
            if (sandbox != null && !sandbox.isFileInScope(filePath)) {
                return ToolResult.error("文件不在审查范围内: " + filePath);
            }
            if (!Files.isRegularFile(fullPath)) {
                return ToolResult.error("文件不存在: " + filePath);
            }

            String content = Files.readString(fullPath, StandardCharsets.UTF_8);
            ASTAnalysisResult result = analyzer.analyze(filePath, content);

            if (!result.isParseSucceeded()) {
                return ToolResult.error("解析失败: " + result.getParseError());
            }

            return ToolResult.ok(formatResult(filePath, result));
        } catch (IOException e) {
            return ToolResult.error("读取文件失败: " + e.getMessage());
        }
    }

    private String formatResult(String filePath, ASTAnalysisResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件: ").append(filePath).append("\n");

        // Imports
        if (!result.getImports().isEmpty()) {
            sb.append("\nImports:\n");
            for (String imp : result.getImports()) {
                sb.append("  import ").append(imp).append(";\n");
            }
        }

        // Classes
        for (ClassInfo cls : result.getClasses()) {
            sb.append("\n").append(cls.getType()).append(": ").append(cls.getName());
            if (cls.getSuperClass() != null) {
                sb.append(" extends ").append(cls.getSuperClass());
            }
            if (!cls.getInterfaces().isEmpty()) {
                sb.append(" implements ").append(String.join(", ", cls.getInterfaces()));
            }
            sb.append(" [L").append(cls.getStartLine()).append("-L").append(cls.getEndLine()).append("]\n");

            if (!cls.getFields().isEmpty()) {
                sb.append("  Fields: ").append(String.join(", ", cls.getFields())).append("\n");
            }
        }

        // Methods
        if (!result.getMethods().isEmpty()) {
            sb.append("\nMethods:\n");
            for (MethodInfo method : result.getMethods()) {
                sb.append("  ");
                if (!method.getVisibility().isEmpty() && !"package-private".equals(method.getVisibility())) {
                    sb.append(method.getVisibility()).append(" ");
                }
                if (!method.getModifiers().isEmpty()) {
                    sb.append(String.join(" ", method.getModifiers())).append(" ");
                }
                sb.append(method.getReturnType()).append(" ")
                        .append(method.getSimpleSignature())
                        .append(" [L").append(method.getStartLine())
                        .append("-L").append(method.getEndLine()).append("]");

                if (!method.getAnnotations().isEmpty()) {
                    sb.append(" @").append(String.join(", @", method.getAnnotations()));
                }
                sb.append("\n");

                // Call edges for this method
                List<String> callees = result.getResolvedCallEdges().stream()
                        .filter(e -> method.getName().equals(e.getCallerMethod()))
                        .map(e -> e.getCalleeScope().isEmpty()
                                ? e.getCalleeMethod()
                                : e.getCalleeScope() + "." + e.getCalleeMethod())
                        .distinct()
                        .collect(Collectors.toList());
                if (!callees.isEmpty()) {
                    sb.append("    -> calls: ").append(String.join(", ", callees)).append("\n");
                }
            }
        }

        return sb.toString();
    }
}
