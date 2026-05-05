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

@DisplayName("GetRelatedFilesTool")
class GetRelatedFilesToolTest {

    @TempDir
    Path tempDir;

    private CodeGraph graph;
    private AgentContext context;
    private GetRelatedFilesTool tool;

    @BeforeEach
    void setUp() {
        graph = new CodeGraph();
        DiffFileEntry entry = new DiffFileEntry("Service.java", "content", 10);
        context = new AgentContext(tempDir, List.of(entry));

        // Build a graph with files, classes, methods, and relationships
        GraphNode fileA = new GraphNode(GraphNode.Type.FILE, "file:ServiceA.java", "ServiceA.java", "ServiceA.java");
        GraphNode fileB = new GraphNode(GraphNode.Type.FILE, "file:ServiceB.java", "ServiceB.java", "ServiceB.java");
        GraphNode classA = new GraphNode(GraphNode.Type.CLASS, "class:ServiceA", "ServiceA", "ServiceA.java");
        GraphNode classB = new GraphNode(GraphNode.Type.CLASS, "class:ServiceB", "ServiceB", "ServiceB.java");
        GraphNode ifaceRepo = new GraphNode(GraphNode.Type.INTERFACE, "class:Repository", "Repository", "Repository.java");
        GraphNode classRepoImpl = new GraphNode(GraphNode.Type.CLASS, "class:RepositoryImpl", "RepositoryImpl", "RepositoryImpl.java");
        GraphNode methodA = new GraphNode(GraphNode.Type.METHOD, "method:ServiceA.doWork()", "doWork", "ServiceA.java");
        GraphNode methodB = new GraphNode(GraphNode.Type.METHOD, "method:ServiceB.process()", "process", "ServiceB.java");

        graph.addNode(fileA);
        graph.addNode(fileB);
        graph.addNode(classA);
        graph.addNode(classB);
        graph.addNode(ifaceRepo);
        graph.addNode(classRepoImpl);
        graph.addNode(methodA);
        graph.addNode(methodB);

        // CONTAINS edges
        graph.addEdge(new GraphEdge(GraphEdge.Type.CONTAINS, "file:ServiceA.java", "class:ServiceA"));
        graph.addEdge(new GraphEdge(GraphEdge.Type.CONTAINS, "file:ServiceB.java", "class:ServiceB"));
        graph.addEdge(new GraphEdge(GraphEdge.Type.CONTAINS, "class:ServiceA", "method:ServiceA.doWork()"));
        graph.addEdge(new GraphEdge(GraphEdge.Type.CONTAINS, "class:ServiceB", "method:ServiceB.process()"));

        // CALLS edge (dependency)
        graph.addEdge(new GraphEdge(GraphEdge.Type.CALLS, "method:ServiceA.doWork()", "method:ServiceB.process()"));

        // IMPORTS edge
        graph.addEdge(new GraphEdge(GraphEdge.Type.IMPORTS, "file:ServiceA.java", "file:ServiceB.java"));

        // IMPLEMENTS edge
        graph.addEdge(new GraphEdge(GraphEdge.Type.IMPLEMENTS, "class:RepositoryImpl", "class:Repository"));

        // EXTENDS edge
        graph.addEdge(new GraphEdge(GraphEdge.Type.EXTENDS, "class:ServiceA", "class:ServiceB"));

        GetCallGraphTool callGraphTool = new GetCallGraphTool(graph);
        tool = new GetRelatedFilesTool(callGraphTool);
    }

    @Nested
    @DisplayName("name() 和 description()")
    class MetadataTest {

        @Test
        @DisplayName("name() 返回 get_related_files")
        void name() {
            assertEquals("get_related_files", tool.name());
        }

        @Test
        @DisplayName("description() 不为空")
        void description() {
            assertNotNull(tool.description());
            assertFalse(tool.description().isEmpty());
        }
    }

    @Nested
    @DisplayName("execute() 文件路径查询")
    class FilePathQuery {

        @Test
        @DisplayName("查找文件的相关依赖和被依赖")
        void findsRelatedFiles() {
            ToolResult result = tool.execute("ServiceA.java", context);

            assertTrue(result.isSuccess());
            String output = result.getOutput();
            assertTrue(output.contains("ServiceA.java"));
        }

        @Test
        @DisplayName("查找文件中类的继承关系")
        void findsInheritanceInFile() {
            ToolResult result = tool.execute("ServiceA.java", context);

            assertTrue(result.isSuccess());
            // ServiceA extends ServiceB, so inheritance info should appear
            assertTrue(result.getOutput().contains("继承"));
        }
    }

    @Nested
    @DisplayName("execute() 类名查询")
    class ClassNameQuery {

        @Test
        @DisplayName("查找类的方法、父类、子类")
        void findsClassRelatedInfo() {
            ToolResult result = tool.execute("ServiceA", context);

            assertTrue(result.isSuccess());
            String output = result.getOutput();
            assertTrue(output.contains("ServiceA"));
            assertTrue(output.contains("doWork"));
        }

        @Test
        @DisplayName("查找接口的实现类")
        void findsImplementations() {
            ToolResult result = tool.execute("Repository", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("RepositoryImpl"));
        }

        @Test
        @DisplayName("查找类的父类")
        void findsParent() {
            ToolResult result = tool.execute("ServiceA", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("继承"));
            assertTrue(result.getOutput().contains("ServiceB"));
        }
    }

    @Nested
    @DisplayName("execute() 不存在的查询")
    class NotFoundQuery {

        @Test
        @DisplayName("不存在的文件和类返回错误")
        void notFoundReturnsError() {
            ToolResult result = tool.execute("NonExistent.java", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("未找到"));
        }

        @Test
        @DisplayName("不存在的类名返回错误")
        void notFoundClassReturnsError() {
            ToolResult result = tool.execute("NonExistentClass", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("未找到"));
        }
    }
}
