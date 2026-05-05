package com.diffguard.domain.agent.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolResult")
class ToolResultTest {

    @Nested
    @DisplayName("ok() 工厂方法")
    class OkFactoryTest {

        @Test
        @DisplayName("ok() 创建成功结果")
        void createsSuccessResult() {
            ToolResult result = ToolResult.ok("file content");

            assertTrue(result.isSuccess());
            assertEquals("file content", result.getOutput());
            assertNull(result.getError());
        }

        @Test
        @DisplayName("ok() 接受空字符串")
        void acceptsEmptyString() {
            ToolResult result = ToolResult.ok("");

            assertTrue(result.isSuccess());
            assertEquals("", result.getOutput());
        }

        @Test
        @DisplayName("ok() 接受多行内容")
        void acceptsMultilineContent() {
            String content = "line1\nline2\nline3";
            ToolResult result = ToolResult.ok(content);

            assertTrue(result.isSuccess());
            assertEquals(content, result.getOutput());
        }
    }

    @Nested
    @DisplayName("error() 工厂方法")
    class ErrorFactoryTest {

        @Test
        @DisplayName("error() 创建失败结果")
        void createsErrorResult() {
            ToolResult result = ToolResult.error("file not found");

            assertFalse(result.isSuccess());
            assertNull(result.getOutput());
            assertEquals("file not found", result.getError());
        }

        @Test
        @DisplayName("error() 接受空错误消息")
        void acceptsEmptyError() {
            ToolResult result = ToolResult.error("");

            assertFalse(result.isSuccess());
            assertEquals("", result.getError());
        }
    }

    @Nested
    @DisplayName("toDisplayString()")
    class ToDisplayStringTest {

        @Test
        @DisplayName("成功结果返回 output")
        void successReturnsOutput() {
            ToolResult result = ToolResult.ok("hello world");
            assertEquals("hello world", result.toDisplayString());
        }

        @Test
        @DisplayName("失败结果返回 Error: 前缀")
        void errorReturnsPrefixedMessage() {
            ToolResult result = ToolResult.error("something went wrong");
            assertEquals("Error: something went wrong", result.toDisplayString());
        }
    }

    @Nested
    @DisplayName("getter 方法")
    class GetterTest {

        @Test
        @DisplayName("成功结果 getters 返回正确值")
        void successGetters() {
            ToolResult result = ToolResult.ok("data");

            assertTrue(result.isSuccess());
            assertEquals("data", result.getOutput());
            assertNull(result.getError());
        }

        @Test
        @DisplayName("失败结果 getters 返回正确值")
        void errorGetters() {
            ToolResult result = ToolResult.error("fail");

            assertFalse(result.isSuccess());
            assertNull(result.getOutput());
            assertEquals("fail", result.getError());
        }
    }
}
