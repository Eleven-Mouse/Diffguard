package com.diffguard.ast;

import com.diffguard.ast.model.*;
import com.diffguard.util.TokenEstimator;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 将 AST 分析结果转换为 LLM 可读的简洁文本上下文。
 * <p>
 * 输出受 token 预算控制，优先展示 diff 区域内的方法和控制流。
 */
public class ASTContextBuilder {

    private static final double MAX_BUDGET_FRACTION = 0.20;
    private static final int ABSOLUTE_MAX_TOKENS = 600;

    private static final Pattern DIFF_HUNK_PATTERN = Pattern.compile("^@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@");

    /**
     * 从 AST 分析结果构建 LLM 可读的上下文文本。
     *
     * @param result      AST 分析结果
     * @param diffContent 原始 diff 内容（用于提取变更行号）
     * @param diffTokens  原始 diff 的 token 数
     * @param provider    LLM 提供商名称
     * @return 格式化的上下文文本，或空字符串
     */
    public String buildContext(ASTAnalysisResult result, String diffContent,
                               int diffTokens, String provider) {
        if (result == null || !result.isParseSucceeded()) {
            return "";
        }

        int tokenBudget = Math.min(
                (int) (diffTokens * MAX_BUDGET_FRACTION),
                ABSOLUTE_MAX_TOKENS
        );
        tokenBudget = Math.max(tokenBudget, 50);

        Set<Integer> changedLines = extractChangedLines(diffContent);

        StringBuilder sb = new StringBuilder();
        sb.append("[AST Context for ").append(result.getFilePath()).append("]\n");

        // 类结构
        appendClassInfo(result, sb);

        // 方法（优先 diff 区域内的）
        appendMethods(result, changedLines, sb);

        // 控制流（仅 diff 区域内的）
        appendControlFlow(result, changedLines, sb);

        sb.append("[/AST Context]");

        String context = sb.toString();
        int contextTokens = TokenEstimator.estimate(context, provider);

        if (contextTokens > tokenBudget) {
            context = trimContext(result, changedLines, tokenBudget, provider);
        }

        return context;
    }

    /**
     * 从 unified diff 文本中提取变更涉及的行号集合。
     */
    Set<Integer> extractChangedLines(String diffContent) {
        Set<Integer> lines = new HashSet<>();
        if (diffContent == null) return lines;

        int currentLine = -1;
        int remaining = 0;

        for (String line : diffContent.split("\n")) {
            Matcher matcher = DIFF_HUNK_PATTERN.matcher(line);
            if (matcher.find()) {
                currentLine = Integer.parseInt(matcher.group(1));
                remaining = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
                continue;
            }

            if (currentLine > 0 && remaining > 0) {
                if (line.startsWith("+") && !line.startsWith("++")) {
                    lines.add(currentLine);
                    currentLine++;
                    remaining--;
                } else if (!line.startsWith("-") && !line.startsWith("\\")) {
                    currentLine++;
                    remaining--;
                }
            }
        }
        return lines;
    }

    private void appendClassInfo(ASTAnalysisResult result, StringBuilder sb) {
        for (ClassInfo cls : result.getClasses()) {
            sb.append(cls.getType()).append(": ").append(cls.getName());
            if (cls.getSuperClass() != null) {
                sb.append(" extends ").append(cls.getSuperClass());
            }
            if (!cls.getInterfaces().isEmpty()) {
                sb.append(" implements ").append(String.join(", ", cls.getInterfaces()));
            }
            sb.append("\n");

            if (!cls.getFields().isEmpty()) {
                sb.append("  Fields: ").append(String.join(", ", cls.getFields())).append("\n");
            }
        }
    }

    private void appendMethods(ASTAnalysisResult result, Set<Integer> changedLines, StringBuilder sb) {
        if (result.getMethods().isEmpty()) return;

        sb.append("  Methods:\n");

        // 构建调用边索引：caller -> list of callees
        Map<String, List<CallEdge>> callEdgesByCaller = result.getCallEdges().stream()
                .collect(Collectors.groupingBy(CallEdge::getCallerMethod));

        // 优先展示包含变更行的方法
        List<MethodInfo> sortedMethods = result.getMethods().stream()
                .sorted((a, b) -> {
                    boolean aOverlaps = overlapsChangedLines(a, changedLines);
                    boolean bOverlaps = overlapsChangedLines(b, changedLines);
                    if (aOverlaps != bOverlaps) return aOverlaps ? -1 : 1;
                    return Integer.compare(a.getStartLine(), b.getStartLine());
                })
                .toList();

        for (MethodInfo method : sortedMethods) {
            sb.append("    ");
            if (!method.getVisibility().isEmpty() && !"package-private".equals(method.getVisibility())) {
                sb.append(method.getVisibility()).append(" ");
            }
            sb.append(method.getReturnType()).append(" ")
                    .append(method.getSimpleSignature())
                    .append(" [L").append(method.getStartLine())
                    .append("-L").append(method.getEndLine()).append("]");

            List<CallEdge> edges = callEdgesByCaller.get(method.getName());
            if (edges != null && !edges.isEmpty()) {
                String callees = edges.stream()
                        .map(CallEdge::getCalleeMethod)
                        .distinct()
                        .collect(Collectors.joining(", "));
                sb.append(" -> calls ").append(callees);
            }
            sb.append("\n");
        }
    }

    private void appendControlFlow(ASTAnalysisResult result, Set<Integer> changedLines, StringBuilder sb) {
        List<ControlFlowNode> relevantNodes = result.getControlFlowNodes().stream()
                .filter(node -> overlapsChangedLines(node, changedLines))
                .toList();

        if (relevantNodes.isEmpty()) return;

        sb.append("  Control Flow (in changed regions):\n");
        for (ControlFlowNode node : relevantNodes) {
            sb.append("    ").append(node.getType())
                    .append(" [L").append(node.getStartLine())
                    .append("-L").append(node.getEndLine()).append("]");
            if (node.getCondition() != null && !node.getCondition().isEmpty()) {
                sb.append(" ").append(node.getCondition());
            }
            sb.append("\n");
        }
    }

    private boolean overlapsChangedLines(MethodInfo method, Set<Integer> changedLines) {
        if (changedLines.isEmpty()) return true;
        return changedLines.stream()
                .anyMatch(line -> line >= method.getStartLine() && line <= method.getEndLine());
    }

    private boolean overlapsChangedLines(ControlFlowNode node, Set<Integer> changedLines) {
        if (changedLines.isEmpty()) return true;
        return changedLines.stream()
                .anyMatch(line -> line >= node.getStartLine() && line <= node.getEndLine());
    }

    /**
     * 当上下文超出 token 预算时，逐级裁剪。
     */
    private String trimContext(ASTAnalysisResult result, Set<Integer> changedLines,
                               int tokenBudget, String provider) {
        // 策略1: 只保留 diff 区域内的方法
        StringBuilder sb = new StringBuilder();
        sb.append("[AST Context for ").append(result.getFilePath()).append("]\n");
        appendClassInfo(result, sb);

        List<MethodInfo> changedMethods = result.getMethods().stream()
                .filter(m -> overlapsChangedLines(m, changedLines))
                .toList();

        if (!changedMethods.isEmpty()) {
            sb.append("  Methods (changed):\n");
            Map<String, List<CallEdge>> edgesByCaller = result.getCallEdges().stream()
                    .collect(Collectors.groupingBy(CallEdge::getCallerMethod));

            for (MethodInfo method : changedMethods) {
                sb.append("    ").append(method.getSimpleSignature())
                        .append(" [L").append(method.getStartLine())
                        .append("-L").append(method.getEndLine()).append("]");
                List<CallEdge> edges = edgesByCaller.get(method.getName());
                if (edges != null && !edges.isEmpty()) {
                    String callees = edges.stream().map(CallEdge::getCalleeMethod).distinct()
                            .collect(Collectors.joining(", "));
                    sb.append(" -> calls ").append(callees);
                }
                sb.append("\n");
            }
        }

        sb.append("[/AST Context]");
        String trimmed = sb.toString();
        if (TokenEstimator.estimate(trimmed, provider) <= tokenBudget) {
            return trimmed;
        }

        // 策略2: 极简模式 - 只保留类名和方法签名
        sb = new StringBuilder();
        sb.append("[AST Context for ").append(result.getFilePath()).append("]\n");
        for (ClassInfo cls : result.getClasses()) {
            sb.append(cls.getType()).append(": ").append(cls.getName()).append("\n");
        }
        if (!result.getMethods().isEmpty()) {
            sb.append("  Methods: ");
            sb.append(result.getMethods().stream()
                    .map(MethodInfo::getSimpleSignature)
                    .collect(Collectors.joining(", ")));
            sb.append("\n");
        }
        sb.append("[/AST Context]");
        return sb.toString();
    }
}
