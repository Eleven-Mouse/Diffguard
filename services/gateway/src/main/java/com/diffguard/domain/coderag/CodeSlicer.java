package com.diffguard.domain.coderag;

import com.diffguard.domain.ast.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 AST 分析结果切片为可检索的 CodeChunk。
 * <p>
 * 支持三种粒度：
 * - FILE：整个文件作为一个 chunk
 * - CLASS：每个类作为一个 chunk
 * - METHOD：每个方法作为一个 chunk
 */
public class CodeSlicer {

    /**
     * 将 AST 分析结果切片为 method 粒度的 chunk。
     */
    public static List<CodeChunk> sliceByMethod(ASTAnalysisResult result, String sourceContent) {
        if (!result.isParseSucceeded()) return List.of();

        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = sourceContent.split("\n");

        for (MethodInfo method : result.getMethods()) {
            ClassInfo best = findMostSpecificClass(method, result.getClasses());
            if (best == null) continue;

            String content = extractLines(lines, method.getStartLine(), method.getEndLine());
            if (content.isBlank()) continue;

            chunks.add(new CodeChunk(
                    "method:" + best.getName() + "." + method.getName()
                            + "(" + String.join(",", method.getParameterTypes()) + ")",
                    CodeChunk.Granularity.METHOD,
                    result.getFilePath(),
                    best.getName(),
                    method.getName(),
                    content,
                    method.getStartLine(),
                    method.getEndLine()
            ));
        }
        return chunks;
    }

    /**
     * 将 AST 分析结果切片为 class 粒度的 chunk。
     */
    public static List<CodeChunk> sliceByClass(ASTAnalysisResult result, String sourceContent) {
        if (!result.isParseSucceeded()) return List.of();

        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = sourceContent.split("\n");

        for (ClassInfo cls : result.getClasses()) {
            String content = extractLines(lines, cls.getStartLine(), cls.getEndLine());
            if (content.isBlank()) continue;

            chunks.add(new CodeChunk(
                    cls.getType() + ":" + cls.getName(),
                    CodeChunk.Granularity.CLASS,
                    result.getFilePath(),
                    cls.getName(),
                    null,
                    content,
                    cls.getStartLine(),
                    cls.getEndLine()
            ));
        }
        return chunks;
    }

    /**
     * 将整个文件作为一个 chunk。
     */
    public static CodeChunk sliceByFile(ASTAnalysisResult result, String sourceContent) {
        return new CodeChunk(
                "file:" + result.getFilePath(),
                CodeChunk.Granularity.FILE,
                result.getFilePath(),
                null,
                null,
                sourceContent,
                1,
                sourceContent.split("\n").length
        );
    }

    /**
     * 多粒度切片：同时生成 method + class 级别的 chunk。
     */
    public static List<CodeChunk> sliceMultiGranularity(ASTAnalysisResult result, String sourceContent) {
        List<CodeChunk> chunks = new ArrayList<>();
        chunks.addAll(sliceByMethod(result, sourceContent));
        chunks.addAll(sliceByClass(result, sourceContent));
        return chunks;
    }

    private static String extractLines(String[] lines, int startLine, int endLine) {
        StringBuilder sb = new StringBuilder();
        // Lines are 1-indexed
        int from = Math.max(1, startLine) - 1;
        int to = Math.min(lines.length, endLine);
        for (int i = from; i < to; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    private static ClassInfo findMostSpecificClass(MethodInfo method, List<ClassInfo> classes) {
        ClassInfo best = null;
        for (ClassInfo cls : classes) {
            if (method.getStartLine() >= cls.getStartLine()
                    && method.getEndLine() <= cls.getEndLine()) {
                if (best == null || (cls.getEndLine() - cls.getStartLine())
                        < (best.getEndLine() - best.getStartLine())) {
                    best = cls;
                }
            }
        }
        return best;
    }
}
