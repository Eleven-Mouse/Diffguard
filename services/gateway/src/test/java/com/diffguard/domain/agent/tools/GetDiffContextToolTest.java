package com.diffguard.domain.agent.tools;

import com.diffguard.domain.agent.core.AgentContext;
import com.diffguard.domain.agent.core.ToolResult;
import com.diffguard.domain.review.model.DiffFileEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GetDiffContextTool")
class GetDiffContextToolTest {

    @TempDir
    Path tempDir;

    private GetDiffContextTool tool;
    private AgentContext context;

    @BeforeEach
    void setUp() {
        tool = new GetDiffContextTool();
        DiffFileEntry entry1 = new DiffFileEntry("src/A.java", "diff content for A", 100);
        DiffFileEntry entry2 = new DiffFileEntry("src/B.java", "diff content for B", 200);
        context = new AgentContext(tempDir, List.of(entry1, entry2));
    }

    @Nested
    @DisplayName("name() 和 description()")
    class MetadataTest {

        @Test
        @DisplayName("name() 返回 get_diff_context")
        void name() {
            assertEquals("get_diff_context", tool.name());
        }

        @Test
        @DisplayName("description() 不为空")
        void description() {
            assertNotNull(tool.description());
            assertFalse(tool.description().isEmpty());
        }
    }

    @Nested
    @DisplayName("execute() summary 查询")
    class SummaryQuery {

        @Test
        @DisplayName("'summary' 返回所有文件摘要")
        void summaryReturnsAllFiles() {
            ToolResult result = tool.execute("summary", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("2"));
            assertTrue(result.getOutput().contains("src/A.java"));
            assertTrue(result.getOutput().contains("src/B.java"));
        }

        @Test
        @DisplayName("'SUMMARY' 大写也有效")
        void summaryCaseInsensitive() {
            ToolResult result = tool.execute("SUMMARY", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("src/A.java"));
        }

        @Test
        @DisplayName("summary 包含文件统计信息")
        void summaryContainsStats() {
            ToolResult result = tool.execute("summary", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("100"));
            assertTrue(result.getOutput().contains("tokens"));
        }

        @Test
        @DisplayName("空 diff 的 summary 显示 0 文件")
        void emptyDiffSummary() {
            AgentContext emptyCtx = new AgentContext(tempDir, List.of());
            ToolResult result = tool.execute("summary", emptyCtx);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("0"));
        }
    }

    @Nested
    @DisplayName("execute() 文件路径查询")
    class FilePathQuery {

        @Test
        @DisplayName("查询存在的文件返回 diff 内容")
        void existingFileReturnsDiff() {
            ToolResult result = tool.execute("src/A.java", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("src/A.java"));
            assertTrue(result.getOutput().contains("diff content for A"));
        }

        @Test
        @DisplayName("查询另一个文件返回其 diff 内容")
        void anotherFileReturnsDiff() {
            ToolResult result = tool.execute("src/B.java", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("diff content for B"));
        }

        @Test
        @DisplayName("查询不存在的文件回退到 summary")
        void unknownFileFallsBackToSummary() {
            ToolResult result = tool.execute("src/NonExistent.java", context);

            assertTrue(result.isSuccess());
            // Falls back to summary since content is empty for unknown file
            assertTrue(result.getOutput().contains("src/A.java"));
        }
    }

    @Nested
    @DisplayName("execute() 边界情况")
    class EdgeCases {

        @Test
        @DisplayName("空白输入回退到 summary")
        void blankInputFallsBackToSummary() {
            ToolResult result = tool.execute("   ", context);

            assertTrue(result.isSuccess());
            // Empty query -> getDiffContent returns "" -> falls to summary
            assertTrue(result.getOutput().contains("src/A.java"));
        }
    }
}
