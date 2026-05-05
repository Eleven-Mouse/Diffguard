package com.diffguard.domain.ast.spi;

import java.util.List;

/**
 * 多语言 AST 解析器 SPI 接口。
 * <p>
 * 替代硬编码的 JavaParser，通过 Java SPI 机制支持多语言扩展。
 * 每种语言实现此接口，在 META-INF/services 中注册即可自动发现。
 * <p>
 * 当前实现:
 * - JavaTreeSitterProvider (基于 JavaParser，向后兼容)
 * - 未来: PythonTreeSitterProvider, GoTreeSitterProvider, JsTsTreeSitterProvider
 */
public interface LanguageASTProvider {

    /**
     * 支持的编程语言。
     */
    enum Language {
        JAVA("java", ".java"),
        PYTHON("python", ".py"),
        GO("go", ".go"),
        JAVASCRIPT("javascript", ".js"),
        TYPESCRIPT("typescript", ".ts"),
        RUST("rust", ".rs");

        private final String id;
        private final String extension;

        Language(String id, String extension) {
            this.id = id;
            this.extension = extension;
        }

        public String getId() { return id; }
        public String getExtension() { return extension; }

        /**
         * 从文件扩展名推断语言。
         */
        public static Language fromExtension(String filePath) {
            if (filePath == null) return JAVA;
            for (Language lang : values()) {
                if (filePath.endsWith(lang.extension)) return lang;
            }
            return JAVA;
        }
    }

    /**
     * 此 Provider 支持的语言。
     */
    Language language();

    /**
     * 解析源代码，返回 AST 节点列表。
     *
     * @param sourceCode 源代码文本
     * @param filePath   文件路径（用于识别语言特性）
     * @return AST 节点列表
     */
    List<ASTNodeInfo> parse(String sourceCode, String filePath);

    /**
     * 从源代码中提取方法/函数定义。
     */
    List<MethodInfo> extractMethods(String sourceCode, String filePath);

    /**
     * 从源代码中提取调用边（谁调用了谁）。
     */
    List<CallEdgeInfo> extractCallEdges(String sourceCode, String filePath);

    // --- 数据模型 ---

    /**
     * 通用 AST 节点信息。
     */
    record ASTNodeInfo(
            String type,
            String name,
            String filePath,
            int startLine,
            int endLine,
            String sourceText
    ) {}

    /**
     * 方法/函数信息。
     */
    record MethodInfo(
            String name,
            String declaringClass,
            String returnType,
            List<String> parameters,
            String filePath,
            int startLine,
            int endLine,
            int complexity
    ) {}

    /**
     * 调用边信息。
     */
    record CallEdgeInfo(
            String callerMethod,
            String callerClass,
            String calleeMethod,
            String calleeClass,
            String filePath,
            int line
    ) {}
}
