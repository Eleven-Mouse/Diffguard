package com.diffguard.coderag;

import com.diffguard.ast.ASTAnalyzer;
import com.diffguard.ast.model.ASTAnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Code RAG 服务门面。
 * <p>
 * 编排代码切片、向量化、存储和检索的完整流程。
 * <p>
 * 使用方式：
 * <pre>
 * CodeRAGService rag = new CodeRAGService();
 * rag.indexProject(projectDir);
 * List<RAGResult> results = rag.search("SQL injection vulnerability", 5);
 * </pre>
 */
public class CodeRAGService {

    private static final Logger log = LoggerFactory.getLogger(CodeRAGService.class);

    private final ASTAnalyzer analyzer = new ASTAnalyzer();
    private final EmbeddingProvider embeddingProvider;
    private final VectorStore vectorStore;

    // chunk ID -> CodeChunk 映射
    private final Map<String, CodeChunk> chunkIndex = new HashMap<>();
    private boolean indexed = false;

    public CodeRAGService() {
        this(new LocalTFIDFProvider(), new InMemoryVectorStore());
    }

    public CodeRAGService(EmbeddingProvider embeddingProvider, VectorStore vectorStore) {
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
    }

    /**
     * 索引整个项目目录。
     */
    public int indexProject(Path projectDir) {
        chunkIndex.clear();
        vectorStore.clear();

        List<Path> javaFiles = scanJavaFiles(projectDir);
        log.info("开始索引 {} 个 Java 文件", javaFiles.size());

        // Phase 1: 解析所有文件并切片
        List<CodeChunk> allChunks = new ArrayList<>();
        List<String> allTexts = new ArrayList<>();

        for (Path file : javaFiles) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String relativePath = projectDir.relativize(file).toString().replace('\\', '/');
                ASTAnalysisResult result = analyzer.analyze(relativePath, content);

                if (!result.isParseSucceeded()) continue;

                List<CodeChunk> chunks = CodeSlicer.sliceMultiGranularity(result, content);
                for (CodeChunk chunk : chunks) {
                    chunkIndex.put(chunk.getId(), chunk);
                    allChunks.add(chunk);
                    allTexts.add(chunk.toSearchableText());
                }
            } catch (IOException e) {
                log.debug("读取文件失败: {} - {}", file, e.getMessage());
            }
        }

        // Phase 2: 构建词表（TF-IDF 需要先统计语料）
        if (embeddingProvider instanceof LocalTFIDFProvider tfidf) {
            tfidf.buildVocabulary(allTexts);
        }

        // Phase 3: 向量化并存储
        float[][] vectors = embeddingProvider.embedBatch(
                allTexts.toArray(new String[0]));
        for (int i = 0; i < allChunks.size(); i++) {
            vectorStore.store(allChunks.get(i).getId(), vectors[i]);
        }

        indexed = true;
        log.info("索引完成：{} 个 chunk，{} 维向量", allChunks.size(), embeddingProvider.dimension());
        return allChunks.size();
    }

    /**
     * 语义搜索：根据查询文本检索最相关的代码。
     *
     * @param query 查询文本（如 "SQL query execution" 或 diff 内容）
     * @param topK  返回数量
     * @return 按相关性降序排列的检索结果
     */
    public List<RAGResult> search(String query, int topK) {
        if (!indexed || vectorStore.size() == 0) {
            return List.of();
        }

        float[] queryVector = embeddingProvider.embed(query);
        List<VectorStore.SearchResult> searchResults = vectorStore.search(queryVector, topK);

        return searchResults.stream()
                .map(sr -> {
                    CodeChunk chunk = chunkIndex.get(sr.chunkId());
                    return chunk != null ? new RAGResult(chunk, sr.score()) : null;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 基于 diff 内容检索相关上下文。
     * <p>
     * 提取 diff 中的变更信息作为查询，自动排除 diff 文件本身。
     *
     * @param diffFilePath 变更文件路径
     * @param diffContent  diff 内容
     * @param topK         返回数量
     * @return 相关代码片段
     */
    public List<RAGResult> searchRelatedCode(String diffFilePath, String diffContent, int topK) {
        List<RAGResult> results = search(diffContent, topK * 2);
        return results.stream()
                .filter(r -> !r.chunk().getFilePath().equals(diffFilePath))
                .limit(topK)
                .toList();
    }

    /**
     * 获取指定文件的所有 chunk。
     */
    public List<CodeChunk> getChunksForFile(String filePath) {
        return chunkIndex.values().stream()
                .filter(c -> c.getFilePath().equals(filePath))
                .toList();
    }

    public boolean isIndexed() { return indexed; }
    public int chunkCount() { return chunkIndex.size(); }

    private List<Path> scanJavaFiles(Path projectDir) {
        List<Path> javaFiles = new ArrayList<>();
        try {
            Files.walkFileTree(projectDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relativePath = projectDir.relativize(file).toString().replace('\\', '/');
                    if (ASTAnalyzer.isJavaFile(relativePath)) {
                        javaFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    return (name.equals("target") || name.equals("build") || name.equals(".git"))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("扫描项目文件失败: {}", e.getMessage());
        }
        return javaFiles;
    }

    /**
     * RAG 检索结果。
     */
    public record RAGResult(CodeChunk chunk, float score) {
        @Override
        public String toString() {
            return String.format("[%.3f] %s %s (L%d-L%d)",
                    score, chunk.getFilePath(), chunk.getId(),
                    chunk.getStartLine(), chunk.getEndLine());
        }
    }
}
