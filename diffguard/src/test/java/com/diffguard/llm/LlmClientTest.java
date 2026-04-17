package com.diffguard.llm;

import com.diffguard.config.ReviewConfig;
import com.diffguard.exception.LlmApiException;
import com.diffguard.llm.provider.LlmProvider;
import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;
import com.diffguard.prompt.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmClient")
class LlmClientTest {

    @Mock
    LlmProvider mockProvider;

    ReviewConfig config;

    @BeforeEach
    void setUp() {
        config = new ReviewConfig();
    }

    private PromptBuilder.PromptContent makePrompt() {
        return new PromptBuilder.PromptContent(
                "system", "user prompt with diff content",
                "zh", "- security", "Test.java", "diff --git a/Test.java");
    }

    private String validJsonResponse() {
        return """
                {
                  "has_critical": false,
                  "summary": "代码审查结果",
                  "issues": [
                    {
                      "severity": "WARNING",
                      "file": "Test.java",
                      "line": 10,
                      "type": "代码质量",
                      "message": "建议优化",
                      "suggestion": "使用更好的方法"
                    }
                  ],
                  "highlights": [],
                  "test_suggestions": []
                }
                """;
    }

    private String criticalJsonResponse() {
        return """
                {
                  "has_critical": true,
                  "summary": "发现严重问题",
                  "issues": [
                    {
                      "severity": "CRITICAL",
                      "file": "Test.java",
                      "line": 5,
                      "type": "安全漏洞",
                      "message": "SQL注入风险",
                      "suggestion": "使用参数化查询"
                    }
                  ],
                  "highlights": [],
                  "test_suggestions": []
                }
                """;
    }

    @Nested
    @DisplayName("JSON 解析")
    class JsonParsing {

        @Test
        @DisplayName("JSON Object 响应正确解析为结构化结果")
        void jsonObjectParsed() throws Exception {
            when(mockProvider.call(anyString(), anyString())).thenReturn(validJsonResponse());

            try (LlmClient client = new LlmClient(mockProvider, config)) {
                ReviewResult result = client.review(List.of(makePrompt()));
                assertEquals(1, result.getIssues().size());
                assertEquals("Test.java", result.getIssues().get(0).getFile());
                assertEquals(Severity.WARNING, result.getIssues().get(0).getSeverity());
            }
        }

        @Test
        @DisplayName("CRITICAL 问题正确解析")
        void criticalIssueParsed() throws Exception {
            when(mockProvider.call(anyString(), anyString())).thenReturn(criticalJsonResponse());

            try (LlmClient client = new LlmClient(mockProvider, config)) {
                ReviewResult result = client.review(List.of(makePrompt()));
                assertTrue(result.hasCriticalIssues());
                assertEquals(Severity.CRITICAL, result.getIssues().get(0).getSeverity());
            }
        }

        @Test
        @DisplayName("非 JSON 响应触发格式化重试")
        void nonJsonTriggersRetry() throws Exception {
            String rawText = "这是一段非 JSON 的审查结果文本";
            when(mockProvider.call(anyString(), anyString()))
                    .thenReturn(rawText)
                    .thenReturn(validJsonResponse());

            try (LlmClient client = new LlmClient(mockProvider, config)) {
                ReviewResult result = client.review(List.of(makePrompt()));
                // 重试成功，应返回结构化结果
                assertEquals(1, result.getIssues().size());
            }
        }
    }

    @Nested
    @DisplayName("并行执行")
    class ParallelExecution {

        @Test
        @DisplayName("多个 prompt 并行处理")
        void multiplePromptsParallel() throws Exception {
            when(mockProvider.call(anyString(), anyString())).thenReturn(validJsonResponse());

            PromptBuilder.PromptContent p1 = makePrompt();
            PromptBuilder.PromptContent p2 = new PromptBuilder.PromptContent(
                    "sys", "user2", "zh", "", "B.java", "diff B");

            try (LlmClient client = new LlmClient(mockProvider, config)) {
                ReviewResult result = client.review(List.of(p1, p2));
                assertEquals(2, result.getIssues().size());
                verify(mockProvider, times(2)).call(anyString(), anyString());
            }
        }

        @Test
        @DisplayName("单个 prompt 不走并行路径")
        void singlePromptSequential() throws Exception {
            when(mockProvider.call(anyString(), anyString())).thenReturn(validJsonResponse());

            try (LlmClient client = new LlmClient(mockProvider, config)) {
                client.review(List.of(makePrompt()));
                verify(mockProvider, times(1)).call(anyString(), anyString());
            }
        }
    }

    @Nested
    @DisplayName("重试机制")
    class RetryMechanism {

        @Test
        @DisplayName("429 错误触发重试最终成功")
        void rateLimitRetry() throws Exception {
            when(mockProvider.call(anyString(), anyString()))
                    .thenThrow(new LlmApiException(429, "rate limited"))
                    .thenReturn(validJsonResponse());

            try (LlmClient client = new LlmClient(mockProvider, config)) {
                ReviewResult result = client.review(List.of(makePrompt()));
                assertEquals(1, result.getIssues().size());
            }
        }

        @Test
        @DisplayName("非重试错误立即抛出")
        void nonRetryableErrorNoRetry() throws Exception {
            when(mockProvider.call(anyString(), anyString()))
                    .thenThrow(new LlmApiException(401, "unauthorized"));

            try (LlmClient client = new LlmClient(mockProvider, config)) {
                assertThrows(LlmApiException.class, () -> client.review(List.of(makePrompt())));
            }
        }

        @Test
        @DisplayName("重试耗尽后抛出异常")
        void retriesExhausted() throws Exception {
            when(mockProvider.call(anyString(), anyString()))
                    .thenThrow(new LlmApiException(429, "rate limited"));

            try (LlmClient client = new LlmClient(mockProvider, config)) {
                assertThrows(LlmApiException.class, () -> client.review(List.of(makePrompt())));
                verify(mockProvider, atLeast(3)).call(anyString(), anyString());
            }
        }
    }

    @Nested
    @DisplayName("资源管理")
    class ResourceManagement {

        @Test
        @DisplayName("close() 正常关闭不抛异常")
        void closeNoException() {
            LlmClient client = new LlmClient(mockProvider, config);
            assertDoesNotThrow(client::close);
        }
    }
}
