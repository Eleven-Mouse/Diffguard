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

@DisplayName("GetFileContentTool")
class GetFileContentToolTest {

    @TempDir
    Path tempDir;

    private AgentContext context;

    @BeforeEach
    void setUp() {
        DiffFileEntry entry = new DiffFileEntry("src/Main.java", "content", 10);
        context = new AgentContext(tempDir, List.of(entry));
    }

    private Path createFile(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Nested
    @DisplayName("name() 和 description()")
    class MetadataTest {

        @Test
        @DisplayName("name() 返回 get_file_content")
        void name() {
            GetFileContentTool tool = new GetFileContentTool(tempDir);
            assertEquals("get_file_content", tool.name());
        }

        @Test
        @DisplayName("description() 不为空")
        void description() {
            GetFileContentTool tool = new GetFileContentTool(tempDir);
            assertNotNull(tool.description());
            assertFalse(tool.description().isEmpty());
        }
    }

    @Nested
    @DisplayName("execute() 正常路径")
    class ExecuteHappyPath {

        @Test
        @DisplayName("读取存在的文件")
        void readsExistingFile() throws IOException {
            createFile("src/Main.java", "public class Main {}");
            GetFileContentTool tool = new GetFileContentTool(tempDir);

            ToolResult result = tool.execute("src/Main.java", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("public class Main {}"));
            assertTrue(result.getOutput().contains("src/Main.java"));
        }

        @Test
        @DisplayName("读取多行文件")
        void readsMultiLineFile() throws IOException {
            String content = "line1\nline2\nline3";
            createFile("src/Multi.java", content);
            GetFileContentTool tool = new GetFileContentTool(tempDir);

            ToolResult result = tool.execute("src/Multi.java", context);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("line1"));
            assertTrue(result.getOutput().contains("line3"));
        }
    }

    @Nested
    @DisplayName("execute() 错误路径")
    class ExecuteErrorPath {

        @Test
        @DisplayName("空路径返回错误")
        void emptyPathReturnsError() {
            GetFileContentTool tool = new GetFileContentTool(tempDir);

            ToolResult result = tool.execute("", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("不能为空"));
        }

        @Test
        @DisplayName("仅空白路径返回错误")
        void whitespacePathReturnsError() {
            GetFileContentTool tool = new GetFileContentTool(tempDir);

            ToolResult result = tool.execute("   ", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("不能为空"));
        }

        @Test
        @DisplayName(".. 路径穿越被拒绝")
        void pathTraversalBlocked() {
            GetFileContentTool tool = new GetFileContentTool(tempDir);

            ToolResult result = tool.execute("../../etc/passwd", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("不允许"));
        }

        @Test
        @DisplayName("不存在的文件返回错误")
        void nonexistentFileReturnsError() {
            GetFileContentTool tool = new GetFileContentTool(tempDir);

            ToolResult result = tool.execute("src/NotFound.java", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("不存在"));
        }

        @Test
        @DisplayName("超大文件返回错误")
        void oversizedFileReturnsError() throws IOException {
            // Create a file larger than 100KB
            byte[] largeContent = new byte[101_000];
            Path largeFile = tempDir.resolve("src/Large.java");
            Files.createDirectories(largeFile.getParent());
            Files.write(largeFile, largeContent);

            GetFileContentTool tool = new GetFileContentTool(tempDir);

            ToolResult result = tool.execute("src/Large.java", context);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("过大"));
        }
    }

    @Nested
    @DisplayName("execute() 沙箱模式")
    class SandboxMode {

        @Test
        @DisplayName("沙箱限制文件访问")
        void sandboxRestrictsAccess() throws IOException {
            createFile("src/Main.java", "public class Main {}");
            createFile("src/Other.java", "public class Other {}");

            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of("src/Main.java"));
            GetFileContentTool tool = new GetFileContentTool(tempDir, sandbox);

            // Allowed file
            ToolResult allowed = tool.execute("src/Main.java", context);
            assertTrue(allowed.isSuccess());

            // Disallowed file
            ToolResult denied = tool.execute("src/Other.java", context);
            assertFalse(denied.isSuccess());
            assertTrue(denied.getError().contains("不在审查范围"));
        }

        @Test
        @DisplayName("无沙箱不限制文件访问")
        void noSandboxAllowsAll() throws IOException {
            createFile("src/Any.java", "public class Any {}");

            GetFileContentTool tool = new GetFileContentTool(tempDir);
            ToolResult result = tool.execute("src/Any.java", context);

            assertTrue(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("路径安全")
    class PathSecurity {

        @Test
        @DisplayName("resolve 后超出项目目录返回错误")
        void pathOutsideProjectBlocked() {
            GetFileContentTool tool = new GetFileContentTool(tempDir);

            // This shouldn't happen with ".." check, but the normalize check is an extra layer
            ToolResult result = tool.execute("../../../etc/passwd", context);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("路径前后空白被 trim")
        void pathTrimmed() throws IOException {
            createFile("src/Main.java", "content");
            GetFileContentTool tool = new GetFileContentTool(tempDir);

            ToolResult result = tool.execute("  src/Main.java  ", context);
            assertTrue(result.isSuccess());
        }
    }
}
