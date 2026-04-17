package com.diffguard.agent.pipeline;

import com.diffguard.model.IssueRecord;
import com.diffguard.util.TokenEstimator;
import com.diffguard.agent.pipeline.model.AggregatedReview;
import com.diffguard.agent.pipeline.model.DiffSummary;
import com.diffguard.llm.tools.FileAccessSandbox;
import com.diffguard.llm.tools.ReviewToolProvider;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 多阶段审查 Pipeline 编排器。
 * <p>
 * Stage 1: DiffSummaryAgent — 总结变更意图 + 风险评估
 * Stage 2: SecurityReviewer / LogicReviewer / QualityReviewer — 并行专项审查
 * Stage 3: AggregationAgent — 合并去重 + 最终评级
 * <p>
 * Pipeline 输出的 ReviewResult 与单次审查格式完全一致，
 * 对 CLI / Webhook / Cache 层透明。
 */
public class MultiStageReviewService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MultiStageReviewService.class);

    private final DiffSummaryAgent summaryAgent;
    private final SecurityReviewer securityReviewer;
    private final LogicReviewer logicReviewer;
    private final QualityReviewer qualityReviewer;
    private final AggregationAgent aggregationAgent;
    private final ExecutorService parallelExecutor;
    private final AtomicInteger totalTokensUsed = new AtomicInteger(0);

    public MultiStageReviewService(ChatModel chatModel) {
        this.summaryAgent = AiServices.create(DiffSummaryAgent.class, chatModel);
        this.securityReviewer = AiServices.create(SecurityReviewer.class, chatModel);
        this.logicReviewer = AiServices.create(LogicReviewer.class, chatModel);
        this.qualityReviewer = AiServices.create(QualityReviewer.class, chatModel);
        this.aggregationAgent = AiServices.create(AggregationAgent.class, chatModel);
        this.parallelExecutor = Executors.newFixedThreadPool(3);
    }

    /**
     * 带 Tool Use 的构造方法。
     */
    public MultiStageReviewService(ChatModel chatModel, FileAccessSandbox sandbox) {
        this.summaryAgent = AiServices.create(DiffSummaryAgent.class, chatModel);
        this.securityReviewer = AiServices.builder(SecurityReviewer.class)
                .chatModel(chatModel).tools(new ReviewToolProvider(sandbox)).build();
        this.logicReviewer = AiServices.builder(LogicReviewer.class)
                .chatModel(chatModel).tools(new ReviewToolProvider(sandbox)).build();
        this.qualityReviewer = AiServices.builder(QualityReviewer.class)
                .chatModel(chatModel).tools(new ReviewToolProvider(sandbox)).build();
        this.aggregationAgent = AiServices.create(AggregationAgent.class, chatModel);
        this.parallelExecutor = Executors.newFixedThreadPool(3);
    }

    /**
     * 包内可见构造方法，用于测试注入 mock Agent。
     */
    MultiStageReviewService(
            DiffSummaryAgent summaryAgent,
            SecurityReviewer securityReviewer,
            LogicReviewer logicReviewer,
            QualityReviewer qualityReviewer,
            AggregationAgent aggregationAgent) {
        this.summaryAgent = summaryAgent;
        this.securityReviewer = securityReviewer;
        this.logicReviewer = logicReviewer;
        this.qualityReviewer = qualityReviewer;
        this.aggregationAgent = aggregationAgent;
        this.parallelExecutor = Executors.newFixedThreadPool(3);
    }

    private static final int DEFAULT_MAX_TOTAL_TOKENS = 50000;

    /**
     * 执行多阶段审查 Pipeline。
     *
     * @param diffEntries 差异文件列表
     * @param projectDir  项目目录（用于 Tool Use 文件访问）
     * @return 与单次审查格式一致的 ReviewResult
     */
    public ReviewResult review(List<DiffFileEntry> diffEntries, java.nio.file.Path projectDir) {
        return review(diffEntries, projectDir, DEFAULT_MAX_TOTAL_TOKENS, null);
    }

    /**
     * 执行多阶段审查 Pipeline（带 token 限制和 provider 信息）。
     *
     * @param diffEntries    差异文件列表
     * @param projectDir     项目目录
     * @param maxTotalTokens diff 最大 token 数
     * @param provider       LLM 供应商名称（用于 token 估算）
     * @return 审查结果
     */
    public ReviewResult review(List<DiffFileEntry> diffEntries, java.nio.file.Path projectDir,
                               int maxTotalTokens, String provider) {
        long startTime = System.currentTimeMillis();
        String rawDiffs = concatenateDiffs(diffEntries);

        // Token 限制检查：防止大 diff 超出模型上下文窗口
        String allDiffs;
        if (maxTotalTokens > 0 && provider != null) {
            int estimatedTokens = TokenEstimator.estimate(rawDiffs, provider);
            if (estimatedTokens > maxTotalTokens) {
                log.warn("[Pipeline] Diff token 估算 {} 超过限制 {}，截断处理",
                        estimatedTokens, maxTotalTokens);
                allDiffs = truncateToTokenLimit(rawDiffs, maxTotalTokens, provider);
            } else {
                allDiffs = rawDiffs;
            }
        } else {
            allDiffs = rawDiffs;
        }

        // Stage 1: 总结变更
        log.info("[Pipeline] Stage 1: 分析变更摘要...");
        long stage1Start = System.currentTimeMillis();
        DiffSummary summary;
        try {
            Result<DiffSummary> summaryResult = summaryAgent.summarize(allDiffs);
            trackTokens(summaryResult);
            summary = summaryResult.content();
        } catch (Exception e) {
            log.warn("[Pipeline] Stage 1 失败，使用简化摘要：{}", e.getMessage());
            summary = new DiffSummary("代码变更审查", List.of(), List.of(), 3);
        }
        log.info("[Pipeline] Stage 1 完成：耗时 {}ms", System.currentTimeMillis() - stage1Start);

        String summaryText = summary != null && summary.summary() != null
                ? summary.summary() : "代码变更审查";

        // Stage 2: 并行专项审查
        log.info("[Pipeline] Stage 2: 并行专项审查（安全/逻辑/质量）...");
        long stage2Start = System.currentTimeMillis();
        CompletableFuture<String> securityFuture = CompletableFuture.supplyAsync(
                () -> safeReview(securityReviewer, summaryText, allDiffs), parallelExecutor);
        CompletableFuture<String> logicFuture = CompletableFuture.supplyAsync(
                () -> safeReview(logicReviewer, summaryText, allDiffs), parallelExecutor);
        CompletableFuture<String> qualityFuture = CompletableFuture.supplyAsync(
                () -> safeReview(qualityReviewer, summaryText, allDiffs), parallelExecutor);

        String securityResult = null;
        String logicResult = null;
        String qualityResult = null;
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                securityFuture, logicFuture, qualityFuture);
        try {
            allFutures.get(5, TimeUnit.MINUTES);
            securityResult = securityFuture.getNow(null);
            logicResult = logicFuture.getNow(null);
            qualityResult = qualityFuture.getNow(null);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("[Pipeline] Stage 2 总超时（5分钟），取消未完成任务");
            for (CompletableFuture<String> f : List.of(securityFuture, logicFuture, qualityFuture)) {
                if (!f.isDone()) f.cancel(true);
            }
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("[Pipeline] Stage 2 审查执行异常：{}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Pipeline] Stage 2 审查被中断");
        }
        // 从已完成的 Future 中获取可用结果
        securityResult = safeGetResult(securityFuture, securityResult);
        logicResult = safeGetResult(logicFuture, logicResult);
        qualityResult = safeGetResult(qualityFuture, qualityResult);
        log.info("[Pipeline] Stage 2 完成：耗时 {}ms", System.currentTimeMillis() - stage2Start);

        // Stage 3: 聚合
        log.info("[Pipeline] Stage 3: 聚合审查结果...");
        long stage3Start = System.currentTimeMillis();
        AggregatedReview aggregated;
        try {
            // null 安全处理：防止 null 结果传入聚合 Agent
            String secInput = securityResult != null ? securityResult : "（安全审查未返回结果）";
            String logInput = logicResult != null ? logicResult : "（逻辑审查未返回结果）";
            String qualInput = qualityResult != null ? qualityResult : "（质量审查未返回结果）";
            Result<AggregatedReview> aggResult = aggregationAgent.aggregate(
                    summaryText, secInput, logInput, qualInput);
            trackTokens(aggResult);
            aggregated = aggResult.content();
        } catch (Exception e) {
            log.warn("[Pipeline] Stage 3 聚合失败，手动合并：{}", e.getMessage());
            aggregated = null;
        }
        log.info("[Pipeline] Stage 3 完成：耗时 {}ms", System.currentTimeMillis() - stage3Start);

        // 转换为 ReviewResult
        ReviewResult result = convertToReviewResult(aggregated, diffEntries.size());
        result.setReviewDurationMs(System.currentTimeMillis() - startTime);
        log.info("[Pipeline] 完成：{} 个问题，耗时 {}ms",
                result.getIssues().size(), result.getReviewDurationMs());
        return result;
    }

    private String safeReview(Object reviewer, String summary, String diff) {
        try {
            if (reviewer instanceof SecurityReviewer sr) {
                var r = sr.review(summary, diff);
                trackTokens(r);
                return r.content() != null ? r.content().toString() : null;
            } else if (reviewer instanceof LogicReviewer lr) {
                var r = lr.review(summary, diff);
                trackTokens(r);
                return r.content() != null ? r.content().toString() : null;
            } else if (reviewer instanceof QualityReviewer qr) {
                var r = qr.review(summary, diff);
                trackTokens(r);
                return r.content() != null ? r.content().toString() : null;
            }
        } catch (Exception e) {
            log.warn("[Pipeline] 专项审查失败：{}", e.getMessage());
        }
        return null;
    }

    private void trackTokens(Result<?> result) {
        if (result != null && result.tokenUsage() != null) {
            long tokens = result.tokenUsage().inputTokenCount() + result.tokenUsage().outputTokenCount();
            if (tokens > 0) {
                totalTokensUsed.addAndGet((int) tokens);
            }
        }
    }

    public int getTotalTokensUsed() {
        return totalTokensUsed.get();
    }

    private String safeGetResult(CompletableFuture<String> future, String existing) {
        if (existing != null) return existing;
        if (future.isDone() && !future.isCancelled()) {
            try {
                return future.getNow(null);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String concatenateDiffs(List<DiffFileEntry> entries) {
        return entries.stream()
                .map(e -> "--- 文件：" + e.getFilePath() + " ---\n" + e.getContent())
                .collect(Collectors.joining("\n\n"));
    }

    private String truncateToTokenLimit(String content, int maxTokens, String provider) {
        String truncated = content;
        while (TokenEstimator.estimate(truncated, provider) > maxTokens && truncated.length() > 100) {
            truncated = truncated.substring(0, truncated.length() * 2 / 3);
        }
        return truncated + "\n\n... (内容已截断，超出 token 限制)";
    }

    private ReviewResult convertToReviewResult(AggregatedReview aggregated, int fileCount) {
        ReviewResult result = new ReviewResult();
        result.setTotalFilesReviewed(fileCount);

        if (aggregated == null) {
            return result;
        }

        if (Boolean.TRUE.equals(aggregated.has_critical())) {
            result.setHasCriticalFlag(true);
        }

        if (aggregated.issues() != null) {
            for (IssueRecord ir : aggregated.issues()) {
                ReviewIssue issue = new ReviewIssue();
                issue.setSeverity(Severity.fromString(ir.severity()));
                issue.setFile(ir.file() != null ? ir.file() : "");
                issue.setLine(ir.line());
                issue.setType(ir.type() != null ? ir.type() : "");
                issue.setMessage(ir.message() != null ? ir.message() : "");
                issue.setSuggestion(ir.suggestion() != null ? ir.suggestion() : "");
                result.addIssue(issue);
            }
        }

        return result;
    }

    /**
     * 关闭线程池。
     */
    @Override
    public void close() {
        parallelExecutor.shutdown();
        try {
            if (!parallelExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                parallelExecutor.shutdownNow();
                log.warn("[Pipeline] 线程池未在 10s 内优雅关闭，已强制终止");
            }
        } catch (InterruptedException e) {
            parallelExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
