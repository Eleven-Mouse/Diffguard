package com.diffguard.ast;

import com.diffguard.config.ReviewConfig;
import com.diffguard.model.DiffFileEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ASTEnricherTest {

    private ReviewConfig createDefaultConfig() {
        // 最小化配置，只设置必要的 LLM 部分
        ReviewConfig config = new ReviewConfig();
        ReviewConfig.LlmConfig llm = new ReviewConfig.LlmConfig();
        llm.setProvider("openai");
        config.setLlm(llm);
        return config;
    }

    @Test
    void enrich_javaFile_getsASTContext(@TempDir Path tempDir) throws IOException {
        // 准备源文件
        Path sourceFile = tempDir.resolve("src/Service.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                public class Service {
                    public void process() {
                        validate();
                    }
                    private void validate() {}
                }
                """);

        // 准备 diff 条目
        String diffContent = "diff --git a/src/Service.java\n@@ -1,4 +1,5 @@\n+public class Service {\n+    public void process() {";
        DiffFileEntry entry = new DiffFileEntry("src/Service.java", diffContent, 100);

        ASTEnricher enricher = new ASTEnricher(tempDir, createDefaultConfig());
        List<DiffFileEntry> result = enricher.enrich(List.of(entry));

        assertEquals(1, result.size());
        DiffFileEntry enriched = result.get(0);
        assertTrue(enriched.getContent().contains("[AST Context]"));
        assertTrue(enriched.getContent().contains("Service"));
        assertTrue(enriched.getContent().contains("Original diff below"));
        assertTrue(enriched.getContent().contains(diffContent));
    }

    @Test
    void enrich_nonJavaFile_passedThrough(@TempDir Path tempDir) {
        String diffContent = "diff --git a/config.yml\n+key: value";
        DiffFileEntry entry = new DiffFileEntry("config.yml", diffContent, 50);

        ASTEnricher enricher = new ASTEnricher(tempDir, createDefaultConfig());
        List<DiffFileEntry> result = enricher.enrich(List.of(entry));

        assertEquals(1, result.size());
        assertSame(entry, result.get(0));
    }

    @Test
    void enrich_missingSourceFile_passedThrough(@TempDir Path tempDir) {
        String diffContent = "diff --git a/Deleted.java\n-deleted content";
        DiffFileEntry entry = new DiffFileEntry("Deleted.java", diffContent, 50);

        ASTEnricher enricher = new ASTEnricher(tempDir, createDefaultConfig());
        List<DiffFileEntry> result = enricher.enrich(List.of(entry));

        assertEquals(1, result.size());
        assertSame(entry, result.get(0));
    }

    @Test
    void enrich_mixedEntries_javaEnriched_nonJavaPassed(@TempDir Path tempDir) throws IOException {
        // 准备 Java 源文件
        Path javaFile = tempDir.resolve("App.java");
        Files.writeString(javaFile, "public class App { public void run() {} }");

        DiffFileEntry javaEntry = new DiffFileEntry("App.java", "diff content for App.java", 50);
        DiffFileEntry ymlEntry = new DiffFileEntry("config.yml", "diff content for config.yml", 50);

        ASTEnricher enricher = new ASTEnricher(tempDir, createDefaultConfig());
        List<DiffFileEntry> result = enricher.enrich(List.of(javaEntry, ymlEntry));

        assertEquals(2, result.size());

        // Java 文件被增强
        assertTrue(result.get(0).getContent().contains("[AST Context]"));

        // 非 Java 文件原样传递
        assertSame(ymlEntry, result.get(1));
    }

    @Test
    void enrich_syntaxErrorFile_passedThrough(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("Broken.java");
        Files.writeString(javaFile, "public class Broken { public void method( { }");

        DiffFileEntry entry = new DiffFileEntry("Broken.java", "diff content", 50);

        ASTEnricher enricher = new ASTEnricher(tempDir, createDefaultConfig());
        List<DiffFileEntry> result = enricher.enrich(List.of(entry));

        assertEquals(1, result.size());
        assertSame(entry, result.get(0));
    }

    @Test
    void enrich_emptyList_returnsEmpty() {
        ASTEnricher enricher = new ASTEnricher(Path.of("."), createDefaultConfig());
        List<DiffFileEntry> result = enricher.enrich(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void enrich_enrichedContentContainsOriginalDiff(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("Service.java");
        Files.writeString(javaFile, "public class Service { public void doWork() {} }");

        String originalDiff = "diff --git a/Service.java\n@@ -1,2 +1,3 @@\n+public class Service {\n+    public void doWork() {}";
        DiffFileEntry entry = new DiffFileEntry("Service.java", originalDiff, 80);

        ASTEnricher enricher = new ASTEnricher(tempDir, createDefaultConfig());
        List<DiffFileEntry> result = enricher.enrich(List.of(entry));

        String enrichedContent = result.get(0).getContent();
        // 原始 diff 完整保留
        assertTrue(enrichedContent.contains(originalDiff));
    }

    @Test
    void enrich_generatedJavaFile_passedThrough(@TempDir Path tempDir) {
        DiffFileEntry entry = new DiffFileEntry("target/generated/Source.java", "diff content", 50);

        ASTEnricher enricher = new ASTEnricher(tempDir, createDefaultConfig());
        List<DiffFileEntry> result = enricher.enrich(List.of(entry));

        assertEquals(1, result.size());
        assertSame(entry, result.get(0));
    }
}
