package com.diffguard.ast.model;

/**
 * 方法调用关系边，表示 caller -> callee 的调用。
 */
public class CallEdge {

    private final String callerMethod;
    private final String calleeMethod;
    private final int lineNumber;

    public CallEdge(String callerMethod, String calleeMethod, int lineNumber) {
        this.callerMethod = callerMethod;
        this.calleeMethod = calleeMethod;
        this.lineNumber = lineNumber;
    }

    public String getCallerMethod() { return callerMethod; }
    public String getCalleeMethod() { return calleeMethod; }
    public int getLineNumber() { return lineNumber; }
}
