package com.diffguard.domain.ast.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DataFlowNode")
class DataFlowNodeTest {

    @Test
    @DisplayName("所有 getter 返回构造函数传入的值")
    void allGettersReturnConstructorValues() {
        DataFlowNode node = new DataFlowNode("userName", "String", "ASSIGNMENT", "processUser", 42);

        assertEquals("userName", node.getVariableName());
        assertEquals("String", node.getVariableType());
        assertEquals("ASSIGNMENT", node.getFlowType());
        assertEquals("processUser", node.getContainingMethod());
        assertEquals(42, node.getLineNumber());
    }

    @Test
    @DisplayName("containingMethod getter 存在且可用")
    void containingMethodGetterAccessible() {
        DataFlowNode node = new DataFlowNode("x", "int", "USE", "calculate", 10);
        assertEquals("calculate", node.getContainingMethod());
    }

    @Test
    @DisplayName("lineNumber getter 存在且可用")
    void lineNumberGetterAccessible() {
        DataFlowNode node = new DataFlowNode("x", "int", "USE", "calculate", 99);
        assertEquals(99, node.getLineNumber());
    }
}
