package com.diffguard.domain.review;

import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.infrastructure.llm.LlmClient;
import com.diffguard.domain.review.model.DiffFileEntry;
import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.infrastructure.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 代码审查核心业务逻辑。
 * 从 CLI 层解耦，支持独立测试。
 * <p>
 * 实现 {@link AutoCloseable} 以管理内部 {@link LlmClient} 的生命周期。
 * 调用方应使用 try-with-resources 确保资源释放。
 */
public class ReviewService implements ReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewConfig config;
    private final Path projectDir;
    private final ReviewCache cache;
    private final LlmClient injectedClient; // 外部注入（测试用），不由此实例关闭

    private LlmClient ownedClient; // 本实例创建的客户端，需在 close() 中关闭
    private boolean closed = false;
    private volatile String cachedPromptHash; // lazily computed, cached for reuse

    public ReviewService(ReviewConfig config, Path projectDir, boolean noCache) {
        this.config = config;
        this.projectDir = projectDir;
        this.cache = noCache ? null : new ReviewCache(projectDir);
        this.injectedClient = null;
    }

    /**
     * 包内可见构造方法，用于测试注入 mock LlmClient。
     * 注入的客户端不会在 {@link #close()} 时被关闭。
     */
    ReviewService(ReviewConfig config, Path projectDir, boolean noCache, LlmClient llmClient) {
        this.config = config;
        this.projectDir = projectDir;
        this.cache = noCache ? null : new ReviewCache(projectDir);
        this.injectedClient = llmClient;
    }

    /**
     * {@link ReviewEngine} 统一入口。委托给现有的 {@link #review(List)} 方法。
     */
    @Override
    public ReviewResult review(List<DiffFileEntry> diffEntries, Path projectDir) throws DiffGuardException {
        return review(diffEntries);
    }

    /**
     * 执行代码审查流程：缓存查询 -> Prompt 构建 -> LLM 调用 -> 结果合并 -> 缓存写入。
     *
     * @param diffEntries 待审查的差异文件列表
     * @return 审查结果
     * @throws DiffGuardException 审查过程中的业务异常
     */
    public ReviewResult review(List<DiffFileEntry> diffEntries) throws DiffGuardException {
        ReviewResult result = new ReviewResult();

        // 预计算审查上下文哈希，确保模型/规则/语言/模板变化后不会命中旧缓存
        String promptHash = computePromptHash();
        String contextHash = cache != null
                ? ReviewCache.computeContextHash(
                    config.getLlm().getModel(),
                    config.getRules().getEnabled(),
                    config.getReview().getLanguage(),
                    config.getPipeline().isEnabled(),
                    promptHash)
                : null;

        // 1. 缓存查询：分离已缓存和未缓存的文件
        CacheLookupResult lookup = checkCacheAndPartition(diffEntries, contextHash);
        for (ReviewIssue issue : lookup.cachedIssues()) {
            result.addIssue(issue);
        }
        result.setTotalFilesReviewed(lookup.cacheHits());

        if (lookup.cacheHits() > 0) {
            log.debug("缓存命中 {} 个文件，{} 个文件需重新审查", lookup.cacheHits(), lookup.uncachedEntries().size());
        }

        // 2. 构建提示词并调用 LLM（仅未缓存的文件）
        if (!lookup.uncachedEntries().isEmpty()) {
            callLlmAndMerge(lookup.uncachedEntries(), contextHash, result, lookup.cacheHits());
        }

        return result;
    }

    /**
     * 缓存查询：分离已缓存和未缓存的文件。
     *
     * @param diffEntries  待审查的差异文件列表
     * @param contextHash  审查上下文哈希，用于区分不同配置的缓存
     * @return 缓存查询结果
     */
    private CacheLookupResult checkCacheAndPartition(List<DiffFileEntry> diffEntries, String contextHash) {
        List<ReviewIssue> cachedIssues = new ArrayList<>();
        List<DiffFileEntry> uncachedEntries = new ArrayList<>();
        int cacheHits = 0;

        for (DiffFileEntry entry : diffEntries) {
            if (cache != null) {
                String cacheKey = ReviewCache.buildKey(entry.getFilePath(), entry.getContent(), contextHash);
                List<ReviewIssue> cached = cache.get(cacheKey);
                if (cached != null) {
                    cacheHits++;
                    cachedIssues.addAll(cached);
                    continue;
                }
            }
            uncachedEntries.add(entry);
        }

        return new CacheLookupResult(cachedIssues, uncachedEntries, cacheHits);
    }

    /**
     * 构建提示词、调用 LLM、合并结果并写入缓存。
     *
     * @param uncachedEntries 未命中缓存的文件列表
     * @param contextHash     审查上下文哈希
     * @param result          用于合并的审查结果对象
     * @param cacheHits       已缓存的命中数
     * @throws DiffGuardException 审查过程中的业务异常
     */
    private void callLlmAndMerge(List<DiffFileEntry> uncachedEntries, String contextHash,
                                  ReviewResult result, int cacheHits) throws DiffGuardException {
        if (closed) {
            throw new IllegalStateException("ReviewService 已关闭");
        }
        log.info("开始审查 {} 个文件（共 {} 个批次）",
                uncachedEntries.size(), new PromptBuilder(config, projectDir).buildPrompts(uncachedEntries).size());
        PromptBuilder promptBuilder = new PromptBuilder(config, projectDir);
        List<PromptBuilder.PromptContent> prompts = promptBuilder.buildPrompts(uncachedEntries);

        LlmClient client = resolveClient(uncachedEntries);

        ReviewResult freshResult = client.review(prompts);

        // 合并结果
        for (ReviewIssue issue : freshResult.getIssues()) {
            result.addIssue(issue);
        }
        if (freshResult.getRawReport() != null) {
            result.setRawReport(freshResult.getRawReport());
        }
        if (freshResult.getHasCriticalFlag() != null && freshResult.getHasCriticalFlag()) {
            result.setHasCriticalFlag(true);
        }
        result.setTotalTokensUsed(result.getTotalTokensUsed() + freshResult.getTotalTokensUsed());
        result.setTotalFilesReviewed(result.getTotalFilesReviewed() + freshResult.getTotalFilesReviewed());
        result.setReviewDurationMs(freshResult.getReviewDurationMs());

        // 写入缓存：将批量结果按文件拆分缓存
        if (cache != null) {
            cacheBatchResults(uncachedEntries, freshResult, contextHash);
        }

        log.info("审查完成：{} 个文件，{} 个缓存命中，耗时 {}ms，Token {}",
                result.getTotalFilesReviewed(), cacheHits,
                result.getReviewDurationMs(), result.getTotalTokensUsed());
    }

    /**
     * 缓存查询结果记录。
     */
    private record CacheLookupResult(List<ReviewIssue> cachedIssues, List<DiffFileEntry> uncachedEntries, int cacheHits) {
    }

    /**
     * 获取 LlmClient：优先使用注入的客户端，否则懒创建并复用。
     */
    private LlmClient resolveClient(List<DiffFileEntry> uncachedEntries) {
        if (injectedClient != null) {
            return injectedClient;
        }
        if (ownedClient == null) {
            ownedClient = new LlmClient(config);
        }
        return ownedClient;
    }

    /**
     * 计算 prompt 模板哈希，确保模板变更后缓存失效。
     */
    private String computePromptHash() {
        if (cachedPromptHash != null) return cachedPromptHash;
        PromptBuilder pb = new PromptBuilder(config, projectDir);
        List<PromptBuilder.PromptContent> samples = pb.buildPrompts(
                java.util.List.of(new DiffFileEntry("dummy.java", "dummy diff content", 3)));
        if (samples.isEmpty()) {
            return null;
        }
        PromptBuilder.PromptContent sample = samples.get(0);
        String combined = sample.getSystemPrompt() + sample.getUserPrompt();
        cachedPromptHash = CacheKeyGenerator.sha256(combined);
        return cachedPromptHash;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (ownedClient != null) {
            ownedClient.close();
            ownedClient = null;
        }
    }

    /**
     * 将批量审查结果按文件拆分并写入缓存。
     * 即使多个文件被合并到一次 LLM 调用中，也会按文件分别缓存。
     */
    private void cacheBatchResults(List<DiffFileEntry> entries, ReviewResult result, String contextHash) {
        if (cache == null) return;

        // 分离跨文件/无文件 issue 和有明确文件的 issue
        List<ReviewIssue> crossFileIssues = result.getIssues().stream()
                .filter(issue -> issue.getFile() == null || issue.getFile().isBlank())
                .toList();

        if (entries.size() == 1) {
            // 单文件：直接缓存所有 issue
            DiffFileEntry entry = entries.get(0);
            String cacheKey = ReviewCache.buildKey(entry.getFilePath(), entry.getContent(), contextHash);
            cache.put(cacheKey, result.getIssues());
        } else {
            // 多文件：按文件路径将 issue 分组后分别缓存
            // 跨文件 issue 仅缓存到第一个文件，避免重复
            boolean crossFileIssuesAssigned = false;
            for (DiffFileEntry entry : entries) {
                String filePath = entry.getFilePath();
                List<ReviewIssue> fileIssues = result.getIssues().stream()
                        .filter(issue -> filePath.equals(issue.getFile()))
                        .collect(Collectors.toCollection(ArrayList::new));
                // 跨文件 issue 仅分配到第一个文件，避免重复缓存
                if (!crossFileIssuesAssigned && !crossFileIssues.isEmpty()) {
                    fileIssues.addAll(crossFileIssues);
                    crossFileIssuesAssigned = true;
                }
                String cacheKey = ReviewCache.buildKey(filePath, entry.getContent(), contextHash);
                cache.put(cacheKey, fileIssues);
            }
        }
    }
}
