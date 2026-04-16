package com.diffguard.service;

import com.diffguard.cache.ReviewCache;
import com.diffguard.config.ReviewConfig;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.exception.LlmApiException;
import com.diffguard.llm.LlmClient;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.prompt.PromptBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码审查核心业务逻辑。
 * 从 CLI 层解耦，支持独立测试。
 */
public class ReviewService {

    private final ReviewConfig config;
    private final ReviewCache cache;

    public ReviewService(ReviewConfig config, Path projectDir, boolean noCache) {
        this.config = config;
        this.cache = noCache ? null : new ReviewCache(projectDir);
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
        List<DiffFileEntry> uncachedEntries = new ArrayList<>();

        // 1. 缓存查询：分离已缓存和未缓存的文件
        for (DiffFileEntry entry : diffEntries) {
            if (cache != null) {
                String cacheKey = ReviewCache.buildKey(entry.getFilePath(), entry.getContent());
                List<ReviewIssue> cached = cache.get(cacheKey);
                if (cached != null) {
                    for (ReviewIssue issue : cached) {
                        result.addIssue(issue);
                    }
                    result.setTotalFilesReviewed(result.getTotalFilesReviewed() + 1);
                    continue;
                }
            }
            uncachedEntries.add(entry);
        }

        // 2. 构建提示词并调用 LLM（仅未缓存的文件）
        if (!uncachedEntries.isEmpty()) {
            PromptBuilder promptBuilder = new PromptBuilder(config);
            List<PromptBuilder.PromptContent> prompts = promptBuilder.buildPrompts(uncachedEntries);

            LlmClient llmClient = new LlmClient(config);
            ReviewResult freshResult = llmClient.review(prompts);

            // 3. 合并结果
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

            // 4. 写入缓存：将批量结果按文件拆分缓存
            if (cache != null) {
                cacheBatchResults(uncachedEntries, freshResult);
            }
        }

        return result;
    }

    /**
     * 将批量审查结果按文件拆分并写入缓存。
     * 即使多个文件被合并到一次 LLM 调用中，也会按文件分别缓存。
     */
    private void cacheBatchResults(List<DiffFileEntry> entries, ReviewResult result) {
        if (cache == null) return;

        if (entries.size() == 1) {
            // 单文件：直接缓存所有 issue
            DiffFileEntry entry = entries.get(0);
            String cacheKey = ReviewCache.buildKey(entry.getFilePath(), entry.getContent());
            cache.put(cacheKey, result.getIssues());
        } else {
            // 多文件：按文件路径将 issue 分组后分别缓存
            for (DiffFileEntry entry : entries) {
                String filePath = entry.getFilePath();
                List<ReviewIssue> fileIssues = result.getIssues().stream()
                        .filter(issue -> filePath.equals(issue.getFile()) || issue.getFile() == null || issue.getFile().isBlank())
                        .toList();
                String cacheKey = ReviewCache.buildKey(filePath, entry.getContent());
                cache.put(cacheKey, fileIssues);
            }
        }
    }
}
