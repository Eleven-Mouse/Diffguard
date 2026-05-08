package com.diffguard.domain.coderag;

import com.diffguard.domain.ast.ASTAnalyzer;
import com.diffguard.domain.ast.model.ASTAnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeSlicerTest {

    private final ASTAnalyzer analyzer = new ASTAnalyzer();

    @Test
    void sliceByMethod_extractsMethodChunks() {
        String code = """
                public class UserService {
                    public String getName() { return name; }
                    private void validate(String input) {
                        if (input == null) throw new IllegalArgumentException();
                    }
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("UserService.java", code);
        List<CodeChunk> chunks = CodeSlicer.sliceByMethod(result, code);

        assertEquals(2, chunks.size());
        assertEquals(CodeChunk.Granularity.METHOD, chunks.get(0).getGranularity());

        List<String> methodNames = chunks.stream().map(CodeChunk::getMethodName).toList();
        assertTrue(methodNames.contains("getName"));
        assertTrue(methodNames.contains("validate"));

        for (CodeChunk chunk : chunks) {
            assertEquals("UserService", chunk.getClassName());
            assertEquals("UserService.java", chunk.getFilePath());
            assertFalse(chunk.getContent().isBlank());
        }
    }

    @Test
    void sliceByClass_extractsClassChunks() {
        String code = """
                public class OrderService {
                    public void create() {}
                    public void cancel() {}
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("OrderService.java", code);
        List<CodeChunk> chunks = CodeSlicer.sliceByClass(result, code);

        assertEquals(1, chunks.size());
        assertEquals(CodeChunk.Granularity.CLASS, chunks.get(0).getGranularity());
        assertEquals("OrderService", chunks.get(0).getClassName());
        assertTrue(chunks.get(0).getContent().contains("create"));
        assertTrue(chunks.get(0).getContent().contains("cancel"));
    }

    @Test
    void sliceByFile_returnsWholeFile() {
        String code = "public class App { public void run() {} }";
        ASTAnalysisResult result = analyzer.analyze("App.java", code);

        CodeChunk chunk = CodeSlicer.sliceByFile(result, code);

        assertEquals(CodeChunk.Granularity.FILE, chunk.getGranularity());
        assertEquals("App.java", chunk.getFilePath());
        assertEquals(code, chunk.getContent().trim());
    }

    @Test
    void sliceMultiGranularity_returnsAllLevels() {
        String code = """
                public class Service {
                    public void process() {
                        doWork();
                    }
                    private void doWork() {}
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("Service.java", code);
        List<CodeChunk> chunks = CodeSlicer.sliceMultiGranularity(result, code);

        // 2 methods + 1 class = 3 chunks
        assertEquals(3, chunks.size());

        long methodCount = chunks.stream()
                .filter(c -> c.getGranularity() == CodeChunk.Granularity.METHOD)
                .count();
        long classCount = chunks.stream()
                .filter(c -> c.getGranularity() == CodeChunk.Granularity.CLASS)
                .count();
        assertEquals(2, methodCount);
        assertEquals(1, classCount);
    }

    @Test
    void sliceByMethod_failedParse_returnsEmpty() {
        ASTAnalysisResult result = ASTAnalysisResult.failure("Broken.java", "abc", "syntax error");
        List<CodeChunk> chunks = CodeSlicer.sliceByMethod(result, "broken code");
        assertTrue(chunks.isEmpty());
    }

    // --- P1-12: 嵌套类方法归属 ---

    @Nested
    @DisplayName("嵌套类方法切片归属 (P1-12)")
    class NestedClassSlicingTests {

        @Test
        @DisplayName("嵌套类方法应分配给最具体的内嵌类")
        void sliceByMethod_nestedClass_assignsToMostSpecific() {
            String code = """
                    public class Outer {
                        public void outerMethod() {}

                        public class Inner {
                            public void innerMethod() {}
                        }
                    }
                    """;

            ASTAnalysisResult result = analyzer.analyze("Outer.java", code);
            List<CodeChunk> chunks = CodeSlicer.sliceByMethod(result, code);

            assertEquals(2, chunks.size());

            CodeChunk outerChunk = chunks.stream()
                    .filter(c -> "outerMethod".equals(c.getMethodName()))
                    .findFirst().orElse(null);
            assertNotNull(outerChunk);
            assertEquals("Outer", outerChunk.getClassName());

            CodeChunk innerChunk = chunks.stream()
                    .filter(c -> "innerMethod".equals(c.getMethodName()))
                    .findFirst().orElse(null);
            assertNotNull(innerChunk);
            assertEquals("Inner", innerChunk.getClassName());
        }
    }
}
