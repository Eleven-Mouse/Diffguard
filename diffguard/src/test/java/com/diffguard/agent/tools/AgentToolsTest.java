package com.diffguard.agent.tools;

import com.diffguard.agent.core.AgentContext;
import com.diffguard.agent.core.ToolResult;
import com.diffguard.model.DiffFileEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolsTest {

    @TempDir
    Path tempDir;

    private AgentContext context;

    @BeforeEach
    void setUp() throws IOException {
        // Create sample project files
        writeFile("src/Service.java", """
                import com.example.DAO;
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

    // --- GetFileContentTool ---

    @Nested
    @DisplayName("GetFileContentTool")
    class GetFileContentTests {

        @Test
        @DisplayName("读取存在的文件")
        void readFileContent() {
            GetFileContentTool t = new GetFileContentTool(tempDir);
            ToolResult result = t.execute("src/Service.java", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("public class Service"));
            assertTrue(result.getOutput().contains("dao.save"));
        }

        @Test
        @DisplayName("文件不存在返回错误")
        void fileNotFound() {
            GetFileContentTool t = new GetFileContentTool(tempDir);
            ToolResult result = t.execute("src/NonExistent.java", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("不存在"));
        }

        @Test
        @DisplayName("路径遍历攻击被阻止")
        void pathTraversalBlocked() {
            GetFileContentTool t = new GetFileContentTool(tempDir);
            ToolResult result = t.execute("../../etc/passwd", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("不允许"));
        }

        @Test
        @DisplayName("空路径返回错误")
        void emptyPath() {
            GetFileContentTool t = new GetFileContentTool(tempDir);
            ToolResult result = t.execute("", context);

            assertFalse(result.isSuccess());
        }
    }

    // --- GetDiffContextTool ---

    @Nested
    @DisplayName("GetDiffContextTool")
    class GetDiffContextTests {

        private final GetDiffContextTool tool = new GetDiffContextTool();

        @Test
        @DisplayName("获取 diff 摘要")
        void getDiffSummary() {
            ToolResult result = tool.execute("summary", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("1 个文件"));
            assertTrue(result.getOutput().contains("Service.java"));
        }

        @Test
        @DisplayName("获取指定文件 diff")
        void getSpecificFileDiff() {
            ToolResult result = tool.execute("src/Service.java", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("Service.java"));
        }

        @Test
        @DisplayName("未知查询返回摘要")
        void unknownQueryReturnsSummary() {
            ToolResult result = tool.execute("something random", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("文件"));
        }
    }

    // --- GetMethodDefinitionTool ---

    @Nested
    @DisplayName("GetMethodDefinitionTool")
    class GetMethodDefinitionTests {

        @Test
        @DisplayName("提取方法签名")
        void extractMethodSignatures() {
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir);
            ToolResult result = tool.execute("src/Service.java", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("process"));
            assertTrue(result.getOutput().contains("validate"));
            assertTrue(result.getOutput().contains("DAO"));
        }

        @Test
        @DisplayName("非 Java 文件返回错误")
        void nonJavaFile() {
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir);
            ToolResult result = tool.execute("config.yml", context);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("显示调用关系")
        void showsCallEdges() {
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir);
            ToolResult result = tool.execute("src/Service.java", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("calls"));
        }
    }

    // --- ToolRegistry ---

    @Nested
    @DisplayName("ToolRegistry")
    class ToolRegistryTests {

        @Test
        @DisplayName("注册和获取工具")
        void registerAndGetTools() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new GetDiffContextTool());

            assertEquals(1, registry.size());
            assertTrue(registry.getTool("get_diff_context").isPresent());
            assertEquals("get_diff_context", registry.getToolNames().get(0));
        }

        @Test
        @DisplayName("创建标准工具集")
        void createStandardTools() {
            ToolRegistry registry = ToolRegistry.createStandardTools(tempDir);

            assertEquals(3, registry.size());
            assertTrue(registry.getTool("get_file_content").isPresent());
            assertTrue(registry.getTool("get_diff_context").isPresent());
            assertTrue(registry.getTool("get_method_definition").isPresent());
        }

        @Test
        @DisplayName("工具集可以直接用于 Agent")
        void toolsWorkWithAgent() {
            ToolRegistry registry = ToolRegistry.createStandardTools(tempDir);

            for (var tool : registry.getAllTools()) {
                assertNotNull(tool.name());
                assertNotNull(tool.description());
                assertFalse(tool.name().isEmpty());
                assertFalse(tool.description().isEmpty());
            }
        }
    }

    // --- GetCallGraphTool ---

    @Nested
    @DisplayName("GetCallGraphTool")
    class GetCallGraphTests {

        @Test
        @DisplayName("查找调用方")
        void findCallers() throws IOException {
            writeFile("src/Controller.java", """
                    public class Controller {
                        private Service service;
                        public void handle() { service.process(); }
                    }
                    """);

            GetCallGraphTool tool = new GetCallGraphTool(tempDir);
            ToolResult result = tool.execute("callers process", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("调用方"));
        }

        @Test
        @DisplayName("查找被调用方")
        void findCallees() throws IOException {
            writeFile("src/Controller.java", """
                    public class Controller {
                        public void handle() {}
                    }
                    """);

            GetCallGraphTool tool = new GetCallGraphTool(tempDir);
            ToolResult result = tool.execute("callees process", context);

            assertTrue(result.isSuccess());
        }
    }

    private void writeFile(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
