package com.diffguard.coderag;

/**
 * 代码切片，代表一段可被检索的代码单元。
 */
public class CodeChunk {

    public enum Granularity {
        FILE, CLASS, METHOD
    }

    private final String id;
    private final Granularity granularity;
    private final String filePath;
    private final String className;
    private final String methodName;
    private final String content;
    private final int startLine;
    private final int endLine;

    public CodeChunk(String id, Granularity granularity, String filePath,
                     String className, String methodName,
                     String content, int startLine, int endLine) {
        this.id = id;
        this.granularity = granularity;
        this.filePath = filePath;
        this.className = className;
        this.methodName = methodName;
        this.content = content;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    public String getId() { return id; }
    public Granularity getGranularity() { return granularity; }
    public String getFilePath() { return filePath; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public String getContent() { return content; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }

    /**
     * 构建用于检索的文本：包含元数据 + 代码内容。
     */
    public String toSearchableText() {
        StringBuilder sb = new StringBuilder();
        if (className != null) sb.append(className).append(" ");
        if (methodName != null) sb.append(methodName).append(" ");
        if (filePath != null) sb.append(filePath).append(" ");
        sb.append(content);
        return sb.toString();
    }
}
