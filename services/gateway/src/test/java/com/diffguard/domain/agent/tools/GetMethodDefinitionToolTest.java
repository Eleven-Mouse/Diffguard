package com.diffguard.domain.agent.tools;

import com.diffguard.domain.agent.core.AgentContext;
import com.diffguard.domain.agent.core.ToolResult;
import com.diffguard.domain.review.model.DiffFileEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GetMethodDefinitionTool")
class GetMethodDefinitionToolTest {

    @TempDir
    Path tempDir;

    private AgentContext context;

    @BeforeEach
    void setUp() {
        DiffFileEntry entry = new DiffFileEntry("src/Main.java", "content", 10);
        context = new AgentContext(tempDir, List.of(entry));
    }

    private Path createJavaFile(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Nested
    @DisplayName("name() 和 description()")
    class MetadataTest {

        @Test
        @DisplayName("name() 返回 get_method_definition")
        void name() {
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir);
            assertEquals("get_method_definition", tool.name());
        }

        @Test
        @DisplayName("description() 不为空")
        void description() {
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir);
            assertNotNull(tool.description());
            assertFalse(tool.description().isEmpty());
        }
    }

    @Nested
    @DisplayName("execute() 正常路径")
    class ExecuteHappyPath {

        @Test
        @DisplayName("解析包含类和方法的 Java 文件")
        void parsesJavaFileWithClassAndMethod() throws IOException {
            String javaCode = """
                package com.example;
                import java.util.List;

                public class UserService {
                    private String name;

                    public String getName() {
                        return name;
                    }

                    public void setName(String name) {
                        this.name = name;
                    }
                }
                """;
            createJavaFile("src/UserService.java", javaCode);
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir);

            ToolResult result = tool.execute("src/UserService.java", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("UserService"));
            assertTrue(result.getOutput().contains("getName"));
            assertTrue(result.getOutput().contains("setName"));
        }

        @Test
        @DisplayName("解析包含接口的 Java 文件")
        void parsesInterface() throws IOException {
            String javaCode = """
                package com.example;

                public interface Repository {
                    void save(String data);
                    String load(String id);
                }
                """;
            createJavaFile("src/Repository.java", javaCode);
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir);

            ToolResult result = tool.execute("src/Repository.java", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("Repository"));
        }
    }

    @Nested
    @DisplayName("execute() 错误路径")
    class ExecuteErrorPath {

        @Test
        @DisplayName("空路径返回错误")
        void emptyPathReturnsError() {
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir);

            ToolResult result = tool.execute("", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("不能为空"));
        }

        @Test
        @DisplayName("非 Java 文件返回错误")
        void nonJavaFileReturnsError() {
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir);

            ToolResult result = tool.execute("script.py", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Java"));
        }

        @Test
        @DisplayName("不存在的文件返回错误")
        void nonexistentFileReturnsError() {
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir);

            ToolResult result = tool.execute("NotFound.java", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("不存在"));
        }

        @Test
        @DisplayName("解析无效 Java 代码返回错误")
        void invalidJavaReturnsError() throws IOException {
            createJavaFile("src/Invalid.java", "this is not valid java {{{");
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir);

            ToolResult result = tool.execute("src/Invalid.java", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("解析失败"));
        }
    }

    @Nested
    @DisplayName("execute() 沙箱模式")
    class SandboxMode {

        @Test
        @DisplayName("沙箱限制文件访问")
        void sandboxRestrictsAccess() throws IOException {
            createJavaFile("src/Allowed.java", "public class Allowed {}");
            createJavaFile("src/Blocked.java", "public class Blocked {}");

            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of("src/Allowed.java"));
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir, sandbox);

            // Allowed
            ToolResult allowed = tool.execute("src/Allowed.java", context);
            assertTrue(allowed.isSuccess());

            // Blocked
            ToolResult blocked = tool.execute("src/Blocked.java", context);
            assertFalse(blocked.isSuccess());
            assertTrue(blocked.getError().contains("不在审查范围"));
        }
    }

    @Nested
    @DisplayName("路径安全")
    class PathSecurity {

        @Test
        @DisplayName(".. 路径穿越被拒绝")
        void pathTraversalBlocked() {
            GetMethodDefinitionTool tool = new GetMethodDefinitionTool(tempDir);

            ToolResult result = tool.execute("../../etc/Config.java", context);
            assertFalse(result.isSuccess());
        }
    }
}
