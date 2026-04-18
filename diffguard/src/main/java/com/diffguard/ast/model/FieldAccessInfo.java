package com.diffguard.ast.model;

/**
 * 字段访问信息，追踪方法内的字段读写操作。
 */
public class FieldAccessInfo {

    private final String fieldName;
    private final String accessType;
    private final String containingMethod;
    private final int lineNumber;

    public FieldAccessInfo(String fieldName, String accessType,
                           String containingMethod, int lineNumber) {
        this.fieldName = fieldName;
        this.accessType = accessType;
        this.containingMethod = containingMethod;
        this.lineNumber = lineNumber;
    }

    public String getFieldName() { return fieldName; }
    public String getAccessType() { return accessType; }
    public String getContainingMethod() { return containingMethod; }
    public int getLineNumber() { return lineNumber; }
}
