package com.diffguard.ast.model;

/**
 * 控制流节点，表示 if/for/while/try-catch 等结构。
 */
public class ControlFlowNode {

    private final String type;
    private final int startLine;
    private final int endLine;
    private final String condition;

    public ControlFlowNode(String type, int startLine, int endLine, String condition) {
        this.type = type;
        this.startLine = startLine;
        this.endLine = endLine;
        this.condition = condition;
    }

    public String getType() { return type; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public String getCondition() { return condition; }
}
