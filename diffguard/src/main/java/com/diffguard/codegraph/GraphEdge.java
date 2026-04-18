package com.diffguard.codegraph;

import java.util.Objects;

/**
 * 代码图谱边，表示节点间的关系。
 */
public class GraphEdge {

    public enum Type {
        CALLS,
        IMPLEMENTS,
        EXTENDS,
        IMPORTS,
        CONTAINS
    }

    private final Type type;
    private final String sourceId;
    private final String targetId;
    private final int lineNumber;

    public GraphEdge(Type type, String sourceId, String targetId) {
        this(type, sourceId, targetId, -1);
    }

    public GraphEdge(Type type, String sourceId, String targetId, int lineNumber) {
        this.type = type;
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.lineNumber = lineNumber;
    }

    public Type getType() { return type; }
    public String getSourceId() { return sourceId; }
    public String getTargetId() { return targetId; }
    public int getLineNumber() { return lineNumber; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GraphEdge that)) return false;
        return type == that.type
                && Objects.equals(sourceId, that.sourceId)
                && Objects.equals(targetId, that.targetId)
                && lineNumber == that.lineNumber;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, sourceId, targetId, lineNumber);
    }

    @Override
    public String toString() {
        return sourceId + " -" + type + "-> " + targetId;
    }
}
