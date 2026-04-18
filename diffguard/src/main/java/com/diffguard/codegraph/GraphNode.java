package com.diffguard.codegraph;

import java.util.Objects;

/**
 * 代码图谱节点，表示文件、类、方法或接口。
 */
public class GraphNode {

    public enum Type {
        FILE, CLASS, METHOD, INTERFACE
    }

    private final Type type;
    private final String id;
    private final String name;
    private final String filePath;

    public GraphNode(Type type, String id, String name, String filePath) {
        this.type = type;
        this.id = id;
        this.name = name;
        this.filePath = filePath;
    }

    public Type getType() { return type; }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getFilePath() { return filePath; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GraphNode that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return type + ":" + name + (filePath != null ? " [" + filePath + "]" : "");
    }
}
