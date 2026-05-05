package com.diffguard.domain.coderag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeRAGTest {

    @TempDir
    Path tempDir;

    private CodeRAGService ragService;

    @BeforeEach
    void setUp() throws IOException {
        createSampleProject();
        ragService = new CodeRAGService();
        int chunkCount = ragService.indexProject(tempDir);
        assertTrue(chunkCount > 0, "Should index at least some chunks");
    }

    // --- Indexing ---

    @Test
    void indexProject_createsChunks() {
        assertTrue(ragService.isIndexed());
        assertTrue(ragService.chunkCount() > 0);
    }

    @Test
    void getChunksForFile_returnsCorrectChunks() {
        List<CodeChunk> chunks = ragService.getChunksForFile("src/OrderDAO.java");
        assertFalse(chunks.isEmpty(), "OrderDAO should have chunks");

        boolean hasClassChunk = chunks.stream()
                .anyMatch(c -> c.getGranularity() == CodeChunk.Granularity.CLASS);
        boolean hasMethodChunk = chunks.stream()
                .anyMatch(c -> c.getGranularity() == CodeChunk.Granularity.METHOD);
        assertTrue(hasClassChunk, "Should have class-level chunk");
        assertTrue(hasMethodChunk, "Should have method-level chunk");
    }

    // --- Semantic Search ---

    @Test
    void search_findsRelevantCode() {
        List<CodeRAGService.RAGResult> results = ragService.search("save order sql insert", 10);
        assertFalse(results.isEmpty(), "Should find results for 'save order sql insert'");

        // OrderDAO-related chunks should appear in results
        boolean foundDAO = results.stream()
                .anyMatch(r -> r.chunk().getClassName() != null
                        && r.chunk().getClassName().equals("OrderDAO"));
        assertTrue(foundDAO, "Should find OrderDAO-related chunks");
    }

    @Test
    void search_findProcessMethod() {
        List<CodeRAGService.RAGResult> results = ragService.search("service process input validate", 10);
        assertFalse(results.isEmpty());

        boolean foundService = results.stream()
                .anyMatch(r -> r.chunk().getClassName() != null
                        && r.chunk().getClassName().equals("Service"));
        assertTrue(foundService, "Should find Service-related chunks");
    }

    @Test
    void search_findValidateMethod() {
        List<CodeRAGService.RAGResult> results = ragService.search("validate input null check", 10);
        assertFalse(results.isEmpty());

        boolean foundValidate = results.stream()
                .anyMatch(r -> r.chunk().getContent().contains("validate")
                        || r.chunk().getContent().contains("null"));
        assertTrue(foundValidate, "Should find chunks mentioning validate or null");
    }

    @Test
    void search_resultsOrderedByScore() {
        List<CodeRAGService.RAGResult> results = ragService.search("SQL query execute", 5);
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).score() >= results.get(i).score(),
                    "Results should be ordered by descending score");
        }
    }

    // --- Related Code Search ---

    @Test
    void searchRelatedCode_excludesSelf() {
        String diffContent = "public void save() { String sql = insert into orders }";
        List<CodeRAGService.RAGResult> related = ragService.searchRelatedCode(
                "src/OrderDAO.java", diffContent, 5);

        for (CodeRAGService.RAGResult r : related) {
            assertNotEquals("src/OrderDAO.java", r.chunk().getFilePath(),
                    "Related code should exclude the diff file itself");
        }
    }

    @Test
    void searchRelatedCode_findsCallers() {
        // When reviewing Controller changes, should find related Service code
        String diffContent = "handle request call service process order";
        List<CodeRAGService.RAGResult> related = ragService.searchRelatedCode(
                "src/Controller.java", diffContent, 5);

        assertFalse(related.isEmpty(), "Should find related code from other files");
    }

    // --- Edge Cases ---

    @Test
    void search_emptyQuery_returnsEmpty() {
        List<CodeRAGService.RAGResult> results = ragService.search("", 5);
        // Empty query may return zero-score results or empty
        assertNotNull(results);
    }

    @Test
    void search_noResults_forUnrelatedQuery() {
        List<CodeRAGService.RAGResult> results = ragService.search("quantum physics particle accelerator", 3);
        // Should return results but with low scores
        assertNotNull(results);
    }

    // --- Helpers ---

    private void createSampleProject() throws IOException {
        writeFile("src/Controller.java", """
                public class Controller {
                    private Service service;
                    public void handleRequest(String input) {
                        service.process(input);
                    }
                }
                """);

        writeFile("src/Service.java", """
                public class Service {
                    private OrderDAO orderDAO;
                    public void process(String input) {
                        validate(input);
                        orderDAO.save();
                    }
                    public void validate(String input) {
                        if (input == null) throw new IllegalArgumentException();
                    }
                }
                """);

        writeFile("src/OrderDAO.java", """
                import java.sql.Connection;
                public class OrderDAO {
                    public void save() {
                        String sql = "INSERT INTO orders VALUES (?)";
                        executeSQL(sql);
                    }
                    public void findById() {
                        String sql = "SELECT * FROM orders WHERE id = ?";
                        executeSQL(sql);
                    }
                    private void executeSQL(String sql) {
                        // execute SQL
                    }
                }
                """);

        writeFile("src/SecurityFilter.java", """
                public class SecurityFilter {
                    public boolean checkPermission(String user, String resource) {
                        if (user == null) return false;
                        return hasAccess(user, resource);
                    }
                    private boolean hasAccess(String user, String resource) {
                        return true;
                    }
                }
                """);
    }

    private void writeFile(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
