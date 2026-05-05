package com.diffguard.domain.agent.tools;

import com.diffguard.domain.agent.core.AgentContext;
import com.diffguard.domain.agent.core.ToolResult;
import com.diffguard.domain.coderag.CodeChunk;
import com.diffguard.domain.coderag.CodeRAGService;
import com.diffguard.domain.review.model.DiffFileEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticSearchTool")
class SemanticSearchToolTest {

    @TempDir
    Path tempDir;

    @Mock
    private CodeRAGService ragService;

    private AgentContext context;

    @BeforeEach
    void setUp() {
        DiffFileEntry entry = new DiffFileEntry("Service.java", "content", 10);
        context = new AgentContext(tempDir, List.of(entry));
    }

    @Nested
    @DisplayName("name() 和 description()")
    class MetadataTest {

        @Test
        @DisplayName("name() 返回 semantic_search")
        void name() {
            SemanticSearchTool tool = new SemanticSearchTool(ragService);
            assertEquals("semantic_search", tool.name());
        }

        @Test
        @DisplayName("description() 不为空")
        void description() {
            SemanticSearchTool tool = new SemanticSearchTool(ragService);
            assertNotNull(tool.description());
            assertFalse(tool.description().isEmpty());
        }
    }

    @Nested
    @DisplayName("execute() 正常搜索")
    class ExecuteHappyPath {

        @Test
        @DisplayName("返回搜索结果")
        void returnsSearchResults() {
            CodeChunk chunk = new CodeChunk(
                    "method:Service.query()", CodeChunk.Granularity.METHOD,
                    "Service.java", "Service", "query",
                    "public void query() { stmt.execute(sql); }",
                    10, 15
            );
            CodeRAGService.RAGResult ragResult = new CodeRAGService.RAGResult(chunk, 0.95f);
            when(ragService.isIndexed()).thenReturn(true);
            when(ragService.search("SQL query", 5)).thenReturn(List.of(ragResult));

            SemanticSearchTool tool = new SemanticSearchTool(ragService);
            ToolResult result = tool.execute("SQL query", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("SQL query"));
            assertTrue(result.getOutput().contains("Service"));
            assertTrue(result.getOutput().contains("0.95"));
            verify(ragService).search("SQL query", 5);
        }

        @Test
        @DisplayName("多个搜索结果全部展示")
        void displaysMultipleResults() {
            CodeChunk chunk1 = new CodeChunk("chunk1", CodeChunk.Granularity.METHOD,
                    "A.java", "A", "method1", "code1", 1, 5);
            CodeChunk chunk2 = new CodeChunk("chunk2", CodeChunk.Granularity.CLASS,
                    "B.java", "B", null, "code2", 10, 20);

            when(ragService.isIndexed()).thenReturn(true);
            when(ragService.search("test", 5)).thenReturn(List.of(
                    new CodeRAGService.RAGResult(chunk1, 0.9f),
                    new CodeRAGService.RAGResult(chunk2, 0.8f)
            ));

            SemanticSearchTool tool = new SemanticSearchTool(ragService);
            ToolResult result = tool.execute("test", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("A.java"));
            assertTrue(result.getOutput().contains("B.java"));
        }
    }

    @Nested
    @DisplayName("execute() 错误路径")
    class ExecuteErrorPath {

        @Test
        @DisplayName("空查询返回错误")
        void emptyQueryReturnsError() {
            SemanticSearchTool tool = new SemanticSearchTool(ragService);
            ToolResult result = tool.execute("", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("不能为空"));
        }

        @Test
        @DisplayName("仅空白的查询返回错误")
        void whitespaceQueryReturnsError() {
            SemanticSearchTool tool = new SemanticSearchTool(ragService);
            ToolResult result = tool.execute("   ", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("不能为空"));
        }

        @Test
        @DisplayName("索引未构建返回错误")
        void notIndexedReturnsError() {
            when(ragService.isIndexed()).thenReturn(false);
            SemanticSearchTool tool = new SemanticSearchTool(ragService);

            ToolResult result = tool.execute("test", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("索引尚未构建"));
        }

        @Test
        @DisplayName("搜索结果为空返回提示")
        void emptyResultsReturnsMessage() {
            when(ragService.isIndexed()).thenReturn(true);
            when(ragService.search("nonexistent", 5)).thenReturn(List.of());

            SemanticSearchTool tool = new SemanticSearchTool(ragService);
            ToolResult result = tool.execute("nonexistent", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("未找到"));
        }
    }

    @Nested
    @DisplayName("搜索结果格式")
    class ResultFormat {

        @Test
        @DisplayName("长内容只展示前 5 行")
        void longContentTruncated() {
            StringBuilder longCode = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                longCode.append("line ").append(i + 1).append("\n");
            }
            CodeChunk chunk = new CodeChunk("chunk1", CodeChunk.Granularity.METHOD,
                    "Service.java", "Service", "method", longCode.toString(), 1, 10);

            when(ragService.isIndexed()).thenReturn(true);
            when(ragService.search("test", 5)).thenReturn(
                    List.of(new CodeRAGService.RAGResult(chunk, 0.85f)));

            SemanticSearchTool tool = new SemanticSearchTool(ragService);
            ToolResult result = tool.execute("test", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("5 more lines"));
        }

        @Test
        @DisplayName("结果显示分数和文件路径")
        void resultShowsScoreAndPath() {
            CodeChunk chunk = new CodeChunk("chunk1", CodeChunk.Granularity.METHOD,
                    "Service.java", "Service", "process", "code", 10, 20);

            when(ragService.isIndexed()).thenReturn(true);
            when(ragService.search("auth", 5)).thenReturn(
                    List.of(new CodeRAGService.RAGResult(chunk, 0.77f)));

            SemanticSearchTool tool = new SemanticSearchTool(ragService);
            ToolResult result = tool.execute("auth", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("0.77"));
            assertTrue(result.getOutput().contains("Service.java"));
            assertTrue(result.getOutput().contains("L10-L20"));
        }
    }
}
