package com.diffguard.agent.reviewagents;

import com.diffguard.agent.core.*;
import com.diffguard.agent.core.ReActAgentService;
import com.diffguard.agent.core.ReActReviewOutput;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.IssueRecord;
import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewAgentsTest {

    @Mock
    ReActAgentService mockService;

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    private AgentContext context;

    private static final IssueRecord WARNING_ISSUE = new IssueRecord(
            "WARNING", "Service.java", 3, "代码质量", "建议优化", "使用更好的方法");

    private static final IssueRecord CRITICAL_ISSUE = new IssueRecord(
            "CRITICAL", "DAO.java", 3, "SQL注入", "字符串拼接SQL", "使用参数化查询");

    private static final ReActReviewOutput NORMAL_OUTPUT = new ReActReviewOutput(
            false, "审查完成", List.of(WARNING_ISSUE), List.of(), List.of());

    private static final ReActReviewOutput CRITICAL_OUTPUT = new ReActReviewOutput(
            true, "发现严重问题", List.of(CRITICAL_ISSUE), List.of(), List.of());

    @BeforeEach
    void setUp() throws IOException {
        writeFile("src/Service.java", """
                public class Service {
                    private DAO dao;
                    public void process(String input) {
                        validate(input);
                        dao.save();
                    }
                    private void validate(String input) {
                        if (input == null) throw new IllegalArgumentException();
                    }
                }
                """);
        writeFile("src/DAO.java", """
                public class DAO {
                    public void save() {
                        String sql = "INSERT INTO orders VALUES (?)";
                    }
                }
                """);

        DiffFileEntry entry = new DiffFileEntry("src/Service.java",
                "diff --git a/src/Service.java\n@@ -1,3 +1,5 @@\n+public void process() {\n+    dao.save();\n+}", 50);
        context = new AgentContext(tempDir, List.of(entry));
    }

    // --- SecurityReviewAgent ---

    @Nested
    @DisplayName("SecurityReviewAgent")
    class SecurityAgentTests {

        @Test
        @DisplayName("安全审查返回结果")
        void securityReviewReturnsResult() {
            when(mockService.review(anyString(), anyString()))
                    .thenReturn(resultOf(CRITICAL_OUTPUT));

            ReActAgent reactAgent = new ReActAgent(List.of(), "security", 8, tp -> mockService);
            SecurityReviewAgent agent = new SecurityReviewAgent(reactAgent);

            AgentResponse response = agent.review(context);

            assertTrue(response.isCompleted());
            assertTrue(response.isHasCritical());
            assertEquals(1, response.getIssues().size());
            assertEquals(Severity.CRITICAL, response.getIssues().get(0).getSeverity());
        }
    }

    // --- PerformanceReviewAgent ---

    @Nested
    @DisplayName("PerformanceReviewAgent")
    class PerformanceAgentTests {

        @Test
        @DisplayName("性能审查返回结果")
        void performanceReviewReturnsResult() {
            when(mockService.review(anyString(), anyString()))
                    .thenReturn(resultOf(NORMAL_OUTPUT));

            ReActAgent reactAgent = new ReActAgent(List.of(), "performance", 8, tp -> mockService);
            PerformanceReviewAgent agent = new PerformanceReviewAgent(reactAgent);

            AgentResponse response = agent.review(context);

            assertTrue(response.isCompleted());
            assertFalse(response.getIssues().isEmpty());
        }
    }

    // --- ArchitectureReviewAgent ---

    @Nested
    @DisplayName("ArchitectureReviewAgent")
    class ArchitectureAgentTests {

        @Test
        @DisplayName("架构审查返回结果")
        void architectureReviewReturnsResult() {
            when(mockService.review(anyString(), anyString()))
                    .thenReturn(resultOf(NORMAL_OUTPUT));

            ReActAgent reactAgent = new ReActAgent(List.of(), "architecture", 8, tp -> mockService);
            ArchitectureReviewAgent agent = new ArchitectureReviewAgent(reactAgent);

            AgentResponse response = agent.review(context);

            assertTrue(response.isCompleted());
            assertFalse(response.getIssues().isEmpty());
        }
    }

    // --- MultiAgentReviewOrchestrator ---

    @Nested
    @DisplayName("MultiAgentReviewOrchestrator")
    class OrchestratorTests {

        @Test
        @DisplayName("多 Agent 并行审查聚合结果")
        void multiAgentReviewAggregates() {
            when(mockService.review(anyString(), anyString()))
                    .thenReturn(resultOf(NORMAL_OUTPUT));

            ReActAgent reactAgent = new ReActAgent(List.of(), "review", 8, tp -> mockService);

            try (TestOrchestrator orchestrator = new TestOrchestrator(reactAgent)) {
                ReviewResult result = orchestrator.review(
                        List.of(new DiffFileEntry("Service.java", "diff content", 50)));

                assertNotNull(result);
                assertTrue(result.getIssues().size() >= 1);
            }
        }

        @Test
        @DisplayName("Critical 问题正确传播到最终结果")
        void criticalIssuePropagates() {
            when(mockService.review(anyString(), anyString()))
                    .thenReturn(resultOf(CRITICAL_OUTPUT));

            ReActAgent reactAgent = new ReActAgent(List.of(), "review", 8, tp -> mockService);

            try (TestOrchestrator orchestrator = new TestOrchestrator(reactAgent)) {
                ReviewResult result = orchestrator.review(
                        List.of(new DiffFileEntry("DAO.java", "diff content", 50)));

                assertTrue(result.hasCriticalIssues());
            }
        }

        @Test
        @DisplayName("单个 Agent 失败不影响其他 Agent")
        void singleAgentFailureDoesNotBlockOthers() {
            ReActAgentService failingService = mock(ReActAgentService.class);
            when(failingService.review(anyString(), anyString()))
                    .thenThrow(new RuntimeException("API error"));

            ReActAgent reactAgent = new ReActAgent(List.of(), "review", 8, tp -> failingService);

            try (TestOrchestrator orchestrator = new TestOrchestrator(reactAgent)) {
                ReviewResult result = orchestrator.review(
                        List.of(new DiffFileEntry("Service.java", "diff content", 50)));

                assertNotNull(result);
            }
        }
    }

    // --- Helpers ---

    private <T> Result<T> resultOf(T content) {
        return Result.<T>builder().content(content).build();
    }

    private void writeFile(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * Test orchestrator that injects a pre-built ReActAgent into all review agents.
     */
    private class TestOrchestrator extends MultiAgentReviewOrchestrator {
        private final ReActAgent reactAgent;

        TestOrchestrator(ReActAgent reactAgent) {
            super(null, tempDir);
            this.reactAgent = reactAgent;
        }

        @Override
        protected java.util.List<NamedAgent> createAgents(java.nio.file.Path agentProjectDir, com.diffguard.agent.strategy.ReviewStrategy strategy) {
            return java.util.List.of(
                    new NamedAgent("Security", new SecurityReviewAgent(reactAgent)::review),
                    new NamedAgent("Performance", new PerformanceReviewAgent(reactAgent)::review),
                    new NamedAgent("Architecture", new ArchitectureReviewAgent(reactAgent)::review)
            );
        }
    }
}
