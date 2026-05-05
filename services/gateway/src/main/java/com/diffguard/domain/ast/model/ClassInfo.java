package com.diffguard.domain.ast.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 类/接口/枚举/记录的结构信息。
 */
public class ClassInfo {

    private final String name;
    private final String type;
    private final String superClass;
    private final List<String> interfaces;
    private final List<String> fields;
    private final int startLine;
    private final int endLine;

    public ClassInfo(String name, String type, String superClass,
                     List<String> interfaces, List<String> fields,
                     int startLine, int endLine) {
        this.name = name;
        this.type = type;
        this.superClass = superClass;
        this.interfaces = Collections.unmodifiableList(new ArrayList<>(interfaces));
        this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
        this.startLine = startLine;
        this.endLine = endLine;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getSuperClass() { return superClass; }
    public List<String> getInterfaces() { return interfaces; }
    public List<String> getFields() { return fields; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
}
