package com.diffguard.domain.agent.tools;

import com.diffguard.domain.agent.core.AgentContext;
import com.diffguard.domain.agent.core.ToolResult;
import com.diffguard.domain.codegraph.CodeGraph;
import com.diffguard.domain.codegraph.GraphEdge;
import com.diffguard.domain.codegraph.GraphNode;
import com.diffguard.domain.review.model.DiffFileEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GetCallGraphTool")
class GetCallGraphToolTest {

    @TempDir
    Path tempDir;

    private CodeGraph graph;
    private AgentContext context;
    private GetCallGraphTool tool;

    @BeforeEach
    void setUp() {
        graph = new CodeGraph();
        DiffFileEntry entry = new DiffFileEntry("Service.java", "content", 10);
        context = new AgentContext(tempDir, List.of(entry));

        // Build a sample graph:
        // Service.process() calls Repository.save() and Validator.validate()
        // Controller.handle() calls Service.process()

        GraphNode fileNode = new GraphNode(GraphNode.Type.FILE, "file:Service.java", "Service.java", "Service.java");
        GraphNode classNode = new GraphNode(GraphNode.Type.CLASS, "class:Service", "Service", "Service.java");
        GraphNode processMethod = new GraphNode(GraphNode.Type.METHOD, "method:Service.process()", "process", "Service.java");
        GraphNode saveMethod = new GraphNode(GraphNode.Type.METHOD, "method:Repository.save()", "save", "Repository.java");
        GraphNode validateMethod = new GraphNode(GraphNode.Type.METHOD, "method:Validator.validate()", "validate", "Validator.java");
        GraphNode handleMethod = new GraphNode(GraphNode.Type.METHOD, "method:Controller.handle()", "handle", "Controller.java");

        graph.addNode(fileNode);
        graph.addNode(classNode);
        graph.addNode(processMethod);
        graph.addNode(saveMethod);
        graph.addNode(validateMethod);
        graph.addNode(handleMethod);

        graph.addEdge(new GraphEdge(GraphEdge.Type.CONTAINS, "file:Service.java", "class:Service"));
        graph.addEdge(new GraphEdge(GraphEdge.Type.CONTAINS, "class:Service", "method:Service.process()"));
        graph.addEdge(new GraphEdge(GraphEdge.Type.CALLS, "method:Service.process()", "method:Repository.save()"));
        graph.addEdge(new GraphEdge(GraphEdge.Type.CALLS, "method:Service.process()", "method:Validator.validate()"));
        graph.addEdge(new GraphEdge(GraphEdge.Type.CALLS, "method:Controller.handle()", "method:Service.process()"));

        tool = new GetCallGraphTool(graph);
    }

    @Nested
    @DisplayName("name() 和 description()")
    class MetadataTest {

        @Test
        @DisplayName("name() 返回 get_call_graph")
        void name() {
            assertEquals("get_call_graph", tool.name());
        }

        @Test
        @DisplayName("description() 不为空")
        void description() {
            assertNotNull(tool.description());
            assertFalse(tool.description().isEmpty());
        }
    }

    @Nested
    @DisplayName("execute() callers 查询")
    class CallersQuery {

        @Test
        @DisplayName("查找方法的调用方")
        void findsCallers() {
            ToolResult result = tool.execute("callers process", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("Controller.handle"));
        }

        @Test
        @DisplayName("无调用方的方法显示无外部调用")
        void noCallers() {
            ToolResult result = tool.execute("callers handle", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("无外部调用方"));
        }

        @Test
        @DisplayName("不存在的方法返回错误")
        void methodNotFound() {
            ToolResult result = tool.execute("callers nonexistent", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("未找到"));
        }
    }

    @Nested
    @DisplayName("execute() callees 查询")
    class CalleesQuery {

        @Test
        @DisplayName("查找方法调用的目标方法")
        void findsCallees() {
            ToolResult result = tool.execute("callees process", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("save"));
            assertTrue(result.getOutput().contains("validate"));
        }

        @Test
        @DisplayName("无被调用方的方法")
        void noCallees() {
            ToolResult result = tool.execute("callees save", context);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("不存在的方法返回错误")
        void methodNotFound() {
            ToolResult result = tool.execute("callees nonexistent", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("未找到"));
        }
    }

    @Nested
    @DisplayName("execute() impact 查询")
    class ImpactQuery {

        @Test
        @DisplayName("计算 Class.method 格式的影响范围")
        void computesImpactForClassDotMethod() {
            ToolResult result = tool.execute("impact Service.process", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("Controller.handle"));
        }

        @Test
        @DisplayName("计算单个方法名的影响范围")
        void computesImpactForMethodName() {
            ToolResult result = tool.execute("impact save", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("Service.process"));
        }

        @Test
        @DisplayName("不存在的方法返回错误")
        void methodNotFound() {
            ToolResult result = tool.execute("impact nonexistent", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("未找到"));
        }
    }

    @Nested
    @DisplayName("execute() 未知查询")
    class UnknownQuery {

        @Test
        @DisplayName("未知格式返回错误")
        void unknownFormatReturnsError() {
            ToolResult result = tool.execute("unknown query", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("未知查询格式"));
        }

        @Test
        @DisplayName("空查询返回错误")
        void emptyQueryReturnsError() {
            ToolResult result = tool.execute("   ", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("未知查询格式"));
        }
    }

    @Nested
    @DisplayName("getCodeGraph()")
    class GetCodeGraphTest {

        @Test
        @DisplayName("返回构造时传入的 CodeGraph")
        void returnsCodeGraph() {
            assertSame(graph, tool.getCodeGraph());
        }
    }
}
