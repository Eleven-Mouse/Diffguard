package com.diffguard.domain.ast;

import com.diffguard.domain.ast.model.ASTAnalysisResult;
import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.adapter.toolserver.model.DiffFileEntry;
import com.diffguard.infrastructure.common.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AST 上下文增强门面。
 * <p>
 * 对每个 Java diff 文件：读取源码 → AST 分析（带缓存）→ 构建 context → 替换 DiffFileEntry。
 * 非 Java 文件和失败情况原样传递，绝不阻塞 review 主流程。
 */
public class ASTEnricher {

    private static final Logger log = LoggerFactory.getLogger(ASTEnricher.class);

    private static final String AST_CONTEXT_HEADER = "[AST Context]";
    private static final String AST_CONTEXT_FOOTER = "[/AST Context]";
    private static final String ORIGINAL_DIFF_SEPARATOR = "\n--- Original diff below ---\n";

    private final ASTAnalyzer analyzer;
    private final ASTContextBuilder contextBuilder;
    private final ASTCache cache;
    private final Path projectDir;
    private final ReviewConfig config;

    public ASTEnricher(Path projectDir, ReviewConfig config) {
        this.projectDir = projectDir;
        this.config = config;
        this.analyzer = new ASTAnalyzer();
        this.contextBuilder = new ASTContextBuilder();
        this.cache = new ASTCache();
    }

    /**
     * 对 diff 条目列表进行 AST 上下文增强。
     * <p>
     * Java 文件会被替换为包含 AST context 的新条目，其他文件原样传递。
     *
     * @param entries 原始 diff 条目
     * @return 增强后的条目列表（新对象，不修改原始条目）
     */
    public List<DiffFileEntry> enrich(List<DiffFileEntry> entries) {
        List<DiffFileEntry> enriched = new ArrayList<>(entries.size());

        int enrichedCount = 0;
        for (DiffFileEntry entry : entries) {
            if (!ASTAnalyzer.isJavaFile(entry.getFilePath())) {
                enriched.add(entry);
                continue;
            }

            DiffFileEntry enrichedEntry = enrichSingleEntry(entry);
            enriched.add(enrichedEntry);
            if (enrichedEntry != entry) {
                enrichedCount++;
            }
        }

        if (enrichedCount > 0) {
            log.info("AST 上下文增强完成：{} 个 Java 文件已注入结构化信息", enrichedCount);
        }

        return enriched;
    }

    private DiffFileEntry enrichSingleEntry(DiffFileEntry entry) {
        try {
            String sourceContent = readSourceFile(entry.getFilePath());
            if (sourceContent == null) {
                log.debug("无法读取源文件，跳过 AST 增强：{}", entry.getFilePath());
                return entry;
            }

            // 检查缓存
            String cacheKey = ASTCache.computeKey(entry.getFilePath(), sourceContent);
            ASTAnalysisResult result = cache.get(cacheKey);

            if (result == null) {
                result = analyzer.analyze(entry.getFilePath(), sourceContent);
                cache.put(cacheKey, result);
            }

            if (!result.isParseSucceeded()) {
                log.debug("AST 解析失败，跳过增强：{}", entry.getFilePath());
                return entry;
            }

            // 构建 AST context
            String provider = config.getLlm().getProvider();
            String astContext = contextBuilder.buildContext(
                    result, entry.getContent(), entry.getTokenCount(), provider);

            if (astContext.isEmpty()) {
                return entry;
            }

            // 构建增强后的内容
            String enrichedContent = AST_CONTEXT_HEADER + "\n"
                    + astContext + "\n"
                    + AST_CONTEXT_FOOTER
                    + ORIGINAL_DIFF_SEPARATOR
                    + entry.getContent();

            int newTokenCount = TokenEstimator.estimate(enrichedContent, provider);
            return new DiffFileEntry(entry.getFilePath(), enrichedContent, newTokenCount);

        } catch (Exception e) {
            log.debug("AST 增强失败，使用原始条目：{} - {}", entry.getFilePath(), e.getMessage());
            return entry;
        }
    }

    /**
     * 从工作目录读取源文件内容。
     *
     * @return 文件内容，或 null（文件不存在或读取失败）
     */
    String readSourceFile(String relativePath) {
        try {
            Path sourcePath = projectDir.resolve(relativePath);
            if (Files.isRegularFile(sourcePath)) {
                return Files.readString(sourcePath, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.debug("读取源文件失败：{} - {}", relativePath, e.getMessage());
        }
        return null;
    }
}
