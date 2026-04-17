package com.diffguard.llm.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FileAccessSandbox")
class FileAccessSandboxTest {

    @TempDir
    Path tempDir;

    private Path createFile(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Nested
    @DisplayName("正常文件访问")
    class NormalAccess {

        @Test
        @DisplayName("读取允许范围内的文件")
        void readFileInScope() throws IOException {
            createFile("src/Main.java", "public class Main {}");
            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of("src/Main.java"));

            String content = sandbox.readFile("src/Main.java");
            assertEquals("public class Main {}", content);
        }

        @Test
        @DisplayName("isFileInScope 对允许文件返回 true")
        void isFileInScopeTrue() throws IOException {
            createFile("A.java", "class A {}");
            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of("A.java"));

            assertTrue(sandbox.isFileInScope("A.java"));
        }

        @Test
        @DisplayName("getProjectRoot 返回规范化绝对路径")
        void projectRootNormalized() {
            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of());
            assertEquals(tempDir.normalize().toAbsolutePath(), sandbox.getProjectRoot());
        }

        @Test
        @DisplayName("getAllowedFiles 返回不可变集合")
        void allowedFilesImmutable() {
            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of("A.java"));
            assertEquals(Set.of("A.java"), sandbox.getAllowedFiles());
            assertThrows(UnsupportedOperationException.class, () ->
                    sandbox.getAllowedFiles().add("B.java"));
        }
    }

    @Nested
    @DisplayName("路径穿越防护")
    class PathTraversalPrevention {

        @Test
        @DisplayName("../ 路径穿越被阻止")
        void dotDotTraversalBlocked() {
            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of("A.java"));
            SecurityException ex = assertThrows(SecurityException.class,
                    () -> sandbox.readFile("../../etc/passwd"));
            assertTrue(ex.getMessage().contains("路径穿越"));
        }

        @Test
        @DisplayName("多层 ../ 穿越被阻止")
        void deepTraversalBlocked() {
            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of("A.java"));
            assertThrows(SecurityException.class,
                    () -> sandbox.readFile("../../../etc/shadow"));
        }

        @Test
        @DisplayName("isFileInScope 对穿越路径返回 false")
        void isFileInScopeTraversal() {
            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of("A.java"));
            assertFalse(sandbox.isFileInScope("../../etc/passwd"));
        }
    }

    @Nested
    @DisplayName("文件范围限制")
    class ScopeValidation {

        @Test
        @DisplayName("非 diff 文件被拒绝访问")
        void fileNotInDiffRejected() throws IOException {
            createFile("A.java", "class A {}");
            createFile("B.java", "class B {}");
            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of("A.java"));

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> sandbox.readFile("B.java"));
            assertTrue(ex.getMessage().contains("不在审查范围"));
        }

        @Test
        @DisplayName("isFileInScope 对非范围文件返回 false")
        void isFileInScopeFalse() throws IOException {
            createFile("A.java", "class A {}");
            createFile("B.java", "class B {}");
            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of("A.java"));

            assertFalse(sandbox.isFileInScope("B.java"));
        }

        @Test
        @DisplayName("空允许列表拒绝所有文件")
        void emptyAllowedSet() throws IOException {
            createFile("A.java", "class A {}");
            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of());

            assertThrows(SecurityException.class, () -> sandbox.readFile("A.java"));
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("不存在的文件抛出 IOException")
        void nonexistentFileThrowsIOException() {
            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of("Missing.java"));
            assertThrows(IOException.class, () -> sandbox.readFile("Missing.java"));
        }
    }
}
