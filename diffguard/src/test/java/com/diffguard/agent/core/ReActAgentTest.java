package com.diffguard.agent.core;

import com.diffguard.agent.tools.AgentFunctionToolProvider;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.IssueRecord;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.Severity;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReActAgentTest {

    @Mock
    ReActAgentService mockService;

    private AgentContext context;

    @BeforeEach
    void setUp() {
        DiffFileEntry entry = new DiffFileEntry("Service.java",
                "diff --git a/Service.java\n@@ -1,3 +1,5 @@\n+public void process() {\n+    dao.save();\n+}", 50);
        context = new AgentContext(Path.of("/project"), List.of(entry));
    }

    // --- ReAct Loop with Function Calling ---

    @Nested
    @DisplayName("Function Calling 推理循环")
    class FunctionCallingLoop {

        @Test
        @DisplayName("LLM 直接返回最终答案（无工具调用）")
        void directFinalAnswer() {
            ReActReviewOutput output = new ReActReviewOutput(
                    false, "代码修改了 process 方法", List.of(), List.of(), List.of());
            when(mockService.review(anyString(), anyString()))
                    .thenReturn(resultOf(output));

            ReActAgent agent = new ReActAgent(List.of(), "system prompt", 5,
                    tp -> mockService);

            AgentResponse response = agent.run(context);

            assertTrue(response.isCompleted());
            assertFalse(response.isHasCritical());
            assertFalse(response.getSummary().isEmpty());
            assertTrue(response.getReasoningTrace().stream()
                    .anyMatch(s -> s.getType() == StepRecord.Type.FINAL_ANSWER));
        }

        @Test
        @DisplayName("LLM 返回带 issues 的审查结果")
        void reviewWithIssues() {
            IssueRecord issue = new IssueRecord("CRITICAL", "Service.java", 10,
                    "SQL注入", "字符串拼接 SQL", "使用参数化查询");
            ReActReviewOutput output = new ReActReviewOutput(
                    true, "发现 SQL 注入风险", List.of(issue), List.of(), List.of());
            when(mockService.review(anyString(), anyString()))
                    .thenReturn(resultOf(output));

            ReActAgent agent = new ReActAgent(List.of(), "sys", 5, tp -> mockService);
            AgentResponse response = agent.run(context);

            assertTrue(response.isCompleted());
            assertTrue(response.isHasCritical());
            assertEquals(1, response.getIssues().size());

            ReviewIssue ri = response.getIssues().get(0);
            assertEquals("SQL注入", ri.getType());
            assertEquals("Service.java", ri.getFile());
            assertEquals(10, ri.getLine());
            assertEquals(Severity.CRITICAL, ri.getSeverity());
        }

        @Test
        @DisplayName("LLM 调用工具后返回结果（工具调用由 Function Calling 自动处理）")
        void toolCallThenFinalAnswer() {
            AgentTool mockTool = createMockTool("get_file_content", "returns file content");

            ReActReviewOutput output = new ReActReviewOutput(
                    false, "代码调用 DAO save 方法，无安全问题", List.of(), List.of(), List.of());
            when(mockService.review(anyString(), anyString()))
                    .thenReturn(resultOf(output));

            ReActAgent agent = new ReActAgent(List.of(mockTool),
                    "你是一个专业的代码审查 Agent。", 5,
                    tp -> mockService);

            AgentResponse response = agent.run(context);

            assertTrue(response.isCompleted());
            // Tool call tracking is handled inside the Function Calling loop
            // The mockService bypasses actual tool calls, so tool count depends on the mock
            assertNotNull(response.getSummary());
        }
    }

    // --- Response Conversion ---

    @Nested
    @DisplayName("响应转换")
    class ResponseConversion {

        @Test
        @DisplayName("正确转换 highlights 和 test_suggestions")
        void convertsHighlightsAndTestSuggestions() {
            ReActReviewOutput output = new ReActReviewOutput(
                    false, "审查完成", List.of(),
                    List.of("良好的错误处理"),
                    List.of("测试 process 方法的正常流程"));
            when(mockService.review(anyString(), anyString()))
                    .thenReturn(resultOf(output));

            ReActAgent agent = new ReActAgent(List.of(), "sys", 5, tp -> mockService);
            AgentResponse response = agent.run(context);

            assertTrue(response.isCompleted());
            assertEquals(1, response.getHighlights().size());
            assertEquals("良好的错误处理", response.getHighlights().get(0));
            assertEquals(1, response.getTestSuggestions().size());
            assertEquals("测试 process 方法的正常流程", response.getTestSuggestions().get(0));
        }

        @Test
        @DisplayName("null 字段安全处理")
        void nullFieldsHandledSafely() {
            ReActReviewOutput output = new ReActReviewOutput(null, null, null, null, null);
            when(mockService.review(anyString(), anyString()))
                    .thenReturn(resultOf(output));

            ReActAgent agent = new ReActAgent(List.of(), "sys", 5, tp -> mockService);
            AgentResponse response = agent.run(context);

            assertTrue(response.isCompleted());
            assertFalse(response.isHasCritical());
            assertEquals("", response.getSummary());
            assertTrue(response.getIssues().isEmpty());
            assertTrue(response.getHighlights().isEmpty());
            assertTrue(response.getTestSuggestions().isEmpty());
        }
    }

    // --- Error Handling ---

    @Nested
    @DisplayName("错误处理")
    class ErrorHandling {

        @Test
        @DisplayName("LLM 调用失败返回 incomplete")
        void llmCallFailure() {
            when(mockService.review(anyString(), anyString()))
                    .thenThrow(new RuntimeException("API error"));

            ReActAgent agent = new ReActAgent(List.of(), "sys", 5, tp -> mockService);
            AgentResponse response = agent.run(context);

            assertFalse(response.isCompleted());
            assertTrue(response.getSummary().contains("执行失败"));
        }

        @Test
        @DisplayName("LLM 返回 null content 返回 incomplete")
        void nullContentReturnsIncomplete() {
            when(mockService.review(anyString(), anyString()))
                    .thenReturn(resultOf(null));

            ReActAgent agent = new ReActAgent(List.of(), "sys", 5, tp -> mockService);
            AgentResponse response = agent.run(context);

            assertFalse(response.isCompleted());
            assertTrue(response.getSummary().contains("空结果"));
        }
    }

    // --- Context ---

    @Nested
    @DisplayName("Agent 上下文")
    class ContextTests {

        @Test
        void contextProvidesDiffFilePaths() {
            List<String> paths = context.getDiffFilePaths();
            assertEquals(1, paths.size());
            assertEquals("Service.java", paths.get(0));
        }

        @Test
        void contextProvidesDiffContent() {
            assertTrue(context.getDiffContent("Service.java").isPresent());
            assertFalse(context.getDiffContent("NonExistent.java").isPresent());
        }

        @Test
        void contextCombinedDiff() {
            String combined = context.getCombinedDiff();
            assertTrue(combined.contains("Service.java"));
            assertTrue(combined.contains("dao.save"));
        }

        @Test
        void contextAttributes() {
            context.setAttribute("key", "value");
            assertEquals("value", context.getAttribute("key"));
        }
    }

    // --- Tool Provider ---

    @Nested
    @DisplayName("AgentFunctionToolProvider")
    class ToolProviderTests {

        @Test
        @DisplayName("工具调用被记录到 trace")
        void toolCallsRecordedToTrace() {
            AgentTool mockTool = createMockTool("get_file_content", "returns file content");
            AgentContext ctx = new AgentContext(Path.of("/project"),
                    List.of(new DiffFileEntry("A.java", "diff", 10)));
            List<StepRecord> trace = new ArrayList<>();

            AgentFunctionToolProvider provider = new AgentFunctionToolProvider(
                    List.of(mockTool), ctx, trace, 20);

            String result = provider.getFileContent("src/A.java");

            assertNotNull(result);
            assertEquals(1, ctx.getToolCallCount());
            // Each tool call creates 2 step records: ACTION + OBSERVATION
            assertEquals(2, trace.size());
            assertEquals(StepRecord.Type.ACTION, trace.get(0).getType());
            assertEquals(StepRecord.Type.OBSERVATION, trace.get(1).getType());
            assertEquals("get_file_content", trace.get(0).getToolName());
        }

        @Test
        @DisplayName("工具调用上限被强制执行")
        void toolCallLimitEnforced() {
            AgentTool mockTool = createMockTool("get_file_content", "returns file content");
            AgentContext ctx = new AgentContext(Path.of("/project"),
                    List.of(new DiffFileEntry("A.java", "diff", 10)), 2);
            List<StepRecord> trace = new ArrayList<>();

            AgentFunctionToolProvider provider = new AgentFunctionToolProvider(
                    List.of(mockTool), ctx, trace, 2);

            // First two calls succeed
            provider.getFileContent("A.java");
            provider.getFileContent("B.java");
            // Third call hits the limit
            String result = provider.getFileContent("C.java");

            assertTrue(result.contains("上限"));
            assertEquals(2, ctx.getToolCallCount());
            assertEquals(4, trace.size()); // 2 calls * 2 records each
        }

        @Test
        @DisplayName("未知工具返回错误")
        void unknownToolReturnsError() {
            AgentContext ctx = new AgentContext(Path.of("/project"),
                    List.of(new DiffFileEntry("A.java", "diff", 10)));
            List<StepRecord> trace = new ArrayList<>();

            AgentFunctionToolProvider provider = new AgentFunctionToolProvider(
                    List.of(), ctx, trace, 20);

            // Call a @Tool method that dispatches to "get_file_content" which doesn't exist
            String result = provider.getFileContent("A.java");

            assertTrue(result.contains("未知工具"));
        }
    }

    // --- Helpers ---

    private <T> Result<T> resultOf(T content) {
        return Result.<T>builder().content(content).build();
    }

    private AgentTool createMockTool(String name, String description) {
        return new AgentTool() {
            @Override
            public String name() { return name; }

            @Override
            public String description() { return description; }

            @Override
            public ToolResult execute(String input, AgentContext ctx) {
                return ToolResult.ok("Mock result for " + input);
            }
        };
    }
}
