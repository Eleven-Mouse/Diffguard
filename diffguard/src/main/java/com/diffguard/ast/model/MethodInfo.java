package com.diffguard.ast.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 方法定义信息，包含签名、行范围、可见性和注解。
 */
public class MethodInfo {

    private final String name;
    private final String returnType;
    private final List<String> parameterTypes;
    private final List<String> parameterNames;
    private final int startLine;
    private final int endLine;
    private final String visibility;
    private final Set<String> modifiers;
    private final List<String> annotations;

    public MethodInfo(String name, String returnType,
                      List<String> parameterTypes, List<String> parameterNames,
                      int startLine, int endLine,
                      String visibility, Set<String> modifiers,
                      List<String> annotations) {
        this.name = name;
        this.returnType = returnType;
        this.parameterTypes = Collections.unmodifiableList(new ArrayList<>(parameterTypes));
        this.parameterNames = Collections.unmodifiableList(new ArrayList<>(parameterNames));
        this.startLine = startLine;
        this.endLine = endLine;
        this.visibility = visibility;
        this.modifiers = Collections.unmodifiableSet(new TreeSet<>(modifiers));
        this.annotations = Collections.unmodifiableList(new ArrayList<>(annotations));
    }

    public String getName() { return name; }
    public String getReturnType() { return returnType; }
    public List<String> getParameterTypes() { return parameterTypes; }
    public List<String> getParameterNames() { return parameterNames; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public String getVisibility() { return visibility; }
    public Set<String> getModifiers() { return modifiers; }
    public List<String> getAnnotations() { return annotations; }

    /**
     * 简洁签名格式，如：processOrder(Order, boolean)
     */
    public String getSimpleSignature() {
        return name + "(" + String.join(", ", parameterTypes) + ")";
    }
}
