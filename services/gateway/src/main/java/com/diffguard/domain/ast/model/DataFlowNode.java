package com.diffguard.domain.ast.model;

/**
 * 数据流节点，追踪方法内的变量赋值和使用。
 */
public class DataFlowNode {

    private final String variableName;
    private final String variableType;
    private final String flowType;
    private final String containingMethod;
    private final int lineNumber;

    public DataFlowNode(String variableName, String variableType,
                        String flowType, String containingMethod, int lineNumber) {
        this.variableName = variableName;
        this.variableType = variableType;
        this.flowType = flowType;
        this.containingMethod = containingMethod;
        this.lineNumber = lineNumber;
    }

    public String getVariableName() { return variableName; }
    public String getVariableType() { return variableType; }
    public String getFlowType() { return flowType; }
    public String getContainingMethod() { return containingMethod; }
    public int getLineNumber() { return lineNumber; }

}
