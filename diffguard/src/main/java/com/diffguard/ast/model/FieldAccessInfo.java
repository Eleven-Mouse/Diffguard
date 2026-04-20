package com.diffguard.ast.model;

/**
 * 字段访问信息，追踪方法内的字段读写操作。
 */
public class FieldAccessInfo {

    private final String fieldName;
    private final String accessType;

    public FieldAccessInfo(String fieldName, String accessType) {
        this.fieldName = fieldName;
        this.accessType = accessType;
    }

    public String getFieldName() { return fieldName; }
    public String getAccessType() { return accessType; }

}
