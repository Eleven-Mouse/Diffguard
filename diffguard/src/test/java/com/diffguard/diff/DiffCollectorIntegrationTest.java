package com.diffguard.diff;

import com.diffguard.config.ReviewConfig;
import com.diffguard.model.DiffFileEntry;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DiffCollector 集成测试：使用真实的 JGit 仓库验证端到端 diff 收集流程。
 */
class DiffCollectorIntegrationTest {

    @TempDir
    Path tempDir;

    private ReviewConfig config;
    private Path repoDir;

    @BeforeEach
    void setUp() throws GitAPIException, IOException {
        config = new ReviewConfig();
        repoDir = tempDir.resolve("test-repo");
        Files.createDirectories(repoDir);

        // 初始化 git 仓库
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            // 初始 commit（需要一个 HEAD）
            Path readme = repoDir.resolve("README.md");
            Files.writeString(readme, "# Test Project\n");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("Initial commit").call();
        }
    }

    @Test
    @DisplayName("collectStagedDiff - 暂存区有变更时返回 diff 条目")
    void stagedDiffReturnsEntries() throws Exception {
        // 修改文件并暂存
        try (Git git = Git.open(repoDir.toFile())) {
            Path file = repoDir.resolve("src/Main.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello\");\n    }\n}\n");
            git.add().addFilepattern("src/Main.java").call();
        }

        List<DiffFileEntry> entries = DiffCollector.collectStagedDiff(repoDir, config);

        assertFalse(entries.isEmpty(), "暂存区有变更时应返回条目");
        assertTrue(entries.stream().anyMatch(e -> e.getFilePath().contains("Main.java")),
                "结果中应包含 Main.java");
    }

    @Test
    @DisplayName("collectStagedDiff - 无变更时返回空列表")
    void stagedDiffNoChanges() throws Exception {
        List<DiffFileEntry> entries = DiffCollector.collectStagedDiff(repoDir, config);
        assertTrue(entries.isEmpty(), "暂存区无变更时应返回空列表");
    }

    @Test
    @DisplayName("collectStagedDiff - 忽略模式生效")
    void stagedDiffRespectsIgnorePatterns() throws Exception {
        // 配置忽略 *.txt 文件
        ReviewConfig customConfig = new ReviewConfig();
        customConfig.getIgnore().setFiles(List.of("**/*.txt"));

        try (Git git = Git.open(repoDir.toFile())) {
            Path javaFile = repoDir.resolve("App.java");
            Files.writeString(javaFile, "class App {}");
            Path txtFile = repoDir.resolve("notes.txt");
            Files.writeString(txtFile, "some notes");
            git.add().addFilepattern(".").call();
        }

        List<DiffFileEntry> entries = DiffCollector.collectStagedDiff(repoDir, customConfig);

        assertTrue(entries.stream().anyMatch(e -> e.getFilePath().equals("App.java")),
                "应包含 Java 文件");
        assertFalse(entries.stream().anyMatch(e -> e.getFilePath().endsWith(".txt")),
                "应忽略 .txt 文件");
    }

    @Test
    @DisplayName("collectStagedDiff - 已删除文件被跳过")
    void stagedDiffSkipsDeletedFiles() throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            // 先添加一个文件并提交
            Path file = repoDir.resolve("temp.java");
            Files.writeString(file, "class Temp {}");
            git.add().addFilepattern("temp.java").call();
            git.commit().setMessage("Add temp file").call();

            // 删除文件并暂存
            Files.delete(file);
            git.rm().addFilepattern("temp.java").call();
        }

        List<DiffFileEntry> entries = DiffCollector.collectStagedDiff(repoDir, config);
        assertFalse(entries.stream().anyMatch(e -> e.getFilePath().equals("temp.java")),
                "已删除文件不应出现在 diff 条目中");
    }

    @Test
    @DisplayName("collectDiffBetweenRefs - 比较两个提交之间的差异")
    void diffBetweenRefs() throws Exception {
        String oldRef;
        String newRef;

        try (Git git = Git.open(repoDir.toFile())) {
            // 记录初始 commit
            oldRef = git.getRepository().resolve("HEAD").getName();

            // 添加新文件并提交
            Path file = repoDir.resolve("Service.java");
            Files.writeString(file, "public class Service {\n    public void run() {}\n}\n");
            git.add().addFilepattern("Service.java").call();
            newRef = git.commit().setMessage("Add Service").call().getName();
        }

        List<DiffFileEntry> entries = DiffCollector.collectDiffBetweenRefs(repoDir, oldRef, newRef, config);

        assertEquals(1, entries.size(), "两个 commit 之间应有 1 个 diff 条目");
        assertEquals("Service.java", entries.get(0).getFilePath());
        assertFalse(entries.get(0).getContent().isBlank(), "diff 内容不应为空");
    }

    @Test
    @DisplayName("collectDiffBetweenRefs - 无差异时返回空列表")
    void diffBetweenRefsNoChanges() throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            String head = git.getRepository().resolve("HEAD").getName();
            // 同一个 commit 比较
            List<DiffFileEntry> entries = DiffCollector.collectDiffBetweenRefs(repoDir, head, head, config);
            assertTrue(entries.isEmpty(), "同一 commit 比较应返回空列表");
        }
    }
}
