package com.diffguard.ast.model;

/**
 * 带类上下文的方法调用边，支持跨文件调用图构建。
 * <p>
 * 相比 CallEdge 增加了 callerClass（调用者所属类）和 calleeScope（调用目标的作用域）。
 */
public class ResolvedCallEdge {

    private final String callerClass;
    private final String callerMethod;
    private final String calleeScope;
    private final String calleeMethod;
    private final int lineNumber;

    public ResolvedCallEdge(String callerClass, String callerMethod,
                            String calleeScope, String calleeMethod,
                            int lineNumber) {
        this.callerClass = callerClass;
        this.callerMethod = callerMethod;
        this.calleeScope = calleeScope;
        this.calleeMethod = calleeMethod;
        this.lineNumber = lineNumber;
    }

    public String getCallerClass() { return callerClass; }
    public String getCallerMethod() { return callerMethod; }
    public String getCalleeScope() { return calleeScope; }
    public String getCalleeMethod() { return calleeMethod; }
    public int getLineNumber() { return lineNumber; }

}
