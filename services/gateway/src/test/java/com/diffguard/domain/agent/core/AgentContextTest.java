package com.diffguard.domain.agent.core;

import com.diffguard.domain.review.model.DiffFileEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentContext")
class AgentContextTest {

    @TempDir
    Path tempDir;

    private DiffFileEntry entry(String path, String content) {
        return new DiffFileEntry(path, content, content.length());
    }

    @Nested
    @DisplayName("构造函数")
    class ConstructorTest {

        @Test
        @DisplayName("构造函数正确设置 projectDir")
        void setsProjectDir() {
            AgentContext ctx = new AgentContext(tempDir, List.of());
            assertEquals(tempDir, ctx.getProjectDir());
        }

        @Test
        @DisplayName("构造函数创建 diffEntries 的防御性拷贝")
        void defensiveCopy() {
            List<DiffFileEntry> entries = new java.util.ArrayList<>(List.of(entry("A.java", "content")));
            AgentContext ctx = new AgentContext(tempDir, entries);

            // Modifying the original list should not affect context
            entries.add(entry("B.java", "more"));
            assertEquals(1, ctx.getDiffEntries().size());
        }

        @Test
        @DisplayName("空 diffEntries 列表创建成功")
        void emptyDiffEntries() {
            AgentContext ctx = new AgentContext(tempDir, Collections.emptyList());
            assertTrue(ctx.getDiffEntries().isEmpty());
        }
    }

    @Nested
    @DisplayName("getDiffFilePaths")
    class GetDiffFilePathsTest {

        @Test
        @DisplayName("返回所有 diff 文件路径")
        void returnsAllPaths() {
            List<DiffFileEntry> entries = List.of(
                    entry("src/A.java", "contentA"),
                    entry("src/B.java", "contentB")
            );
            AgentContext ctx = new AgentContext(tempDir, entries);

            List<String> paths = ctx.getDiffFilePaths();
            assertEquals(2, paths.size());
            assertTrue(paths.contains("src/A.java"));
            assertTrue(paths.contains("src/B.java"));
        }

        @Test
        @DisplayName("空 diff 返回空路径列表")
        void emptyPaths() {
            AgentContext ctx = new AgentContext(tempDir, Collections.emptyList());
            assertTrue(ctx.getDiffFilePaths().isEmpty());
        }
    }

    @Nested
    @DisplayName("getDiffContent")
    class GetDiffContentTest {

        @Test
        @DisplayName("根据文件路径获取内容")
        void getsContentByPath() {
            List<DiffFileEntry> entries = List.of(
                    entry("src/A.java", "public class A {}"),
                    entry("src/B.java", "public class B {}")
            );
            AgentContext ctx = new AgentContext(tempDir, entries);

            assertEquals("public class A {}", ctx.getDiffContent("src/A.java"));
            assertEquals("public class B {}", ctx.getDiffContent("src/B.java"));
        }

        @Test
        @DisplayName("不存在的文件路径返回空字符串")
        void nonExistentPathReturnsEmpty() {
            AgentContext ctx = new AgentContext(tempDir, List.of(entry("A.java", "content")));

            assertEquals("", ctx.getDiffContent("NonExistent.java"));
        }

        @Test
        @DisplayName("空 diff 返回空字符串")
        void emptyDiffReturnsEmpty() {
            AgentContext ctx = new AgentContext(tempDir, Collections.emptyList());
            assertEquals("", ctx.getDiffContent("any.java"));
        }
    }

    @Nested
    @DisplayName("getCombinedDiff")
    class GetCombinedDiffTest {

        @Test
        @DisplayName("合并所有 diff 条目")
        void combinesAllDiffs() {
            List<DiffFileEntry> entries = List.of(
                    entry("A.java", "diff content A"),
                    entry("B.java", "diff content B")
            );
            AgentContext ctx = new AgentContext(tempDir, entries);

            String combined = ctx.getCombinedDiff();
            assertTrue(combined.contains("--- A.java"));
            assertTrue(combined.contains("diff content A"));
            assertTrue(combined.contains("--- B.java"));
            assertTrue(combined.contains("diff content B"));
        }

        @Test
        @DisplayName("空 diff 返回空字符串")
        void emptyDiffReturnsEmptyString() {
            AgentContext ctx = new AgentContext(tempDir, Collections.emptyList());
            assertEquals("", ctx.getCombinedDiff());
        }

        @Test
        @DisplayName("单文件 diff 格式正确")
        void singleFileDiffFormat() {
            AgentContext ctx = new AgentContext(tempDir, List.of(entry("Test.java", "content")));

            String combined = ctx.getCombinedDiff();
            assertTrue(combined.startsWith("--- Test.java\n"));
            assertTrue(combined.contains("content"));
        }
    }

    @Nested
    @DisplayName("Token 追踪")
    class TokenTrackingTest {

        @Test
        @DisplayName("初始 token 使用量为 0")
        void initialTokensZero() {
            AgentContext ctx = new AgentContext(tempDir, List.of());
            assertEquals(0, ctx.getTotalTokensUsed());
        }

        @Test
        @DisplayName("addTokens 累加 token 数")
        void addTokensAccumulates() {
            AgentContext ctx = new AgentContext(tempDir, List.of());
            ctx.addTokens(100);
            assertEquals(100, ctx.getTotalTokensUsed());

            ctx.addTokens(50);
            assertEquals(150, ctx.getTotalTokensUsed());
        }

        @Test
        @DisplayName("addTokens 支持并发累加")
        void concurrentTokenAddition() throws InterruptedException {
            AgentContext ctx = new AgentContext(tempDir, List.of());

            Thread t1 = new Thread(() -> {
                for (int i = 0; i < 100; i++) ctx.addTokens(1);
            });
            Thread t2 = new Thread(() -> {
                for (int i = 0; i < 100; i++) ctx.addTokens(1);
            });

            t1.start();
            t2.start();
            t1.join();
            t2.join();

            assertEquals(200, ctx.getTotalTokensUsed());
        }
    }
}
