package com.diffguard.agent.pipeline;

import com.diffguard.agent.tools.FileAccessSandbox;
import com.diffguard.agent.tools.UnifiedToolProvider;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.ReviewResult;
import com.diffguard.agent.pipeline.model.AggregatedReview;
import com.diffguard.agent.pipeline.model.DiffSummary;
import com.diffguard.util.TokenEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import com.diffguard.concurrent.ExecutorManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
public class MultiStageReviewService implements com.diffguard.review.ReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(MultiStageReviewService.class);
    private static final int DEFAULT_MAX_TOTAL_TOKENS = 50000;

    private final DiffSummaryAgent summaryAgent;
    private final SecurityReviewer securityReviewer;
    private final LogicReviewer logicReviewer;
    private final QualityReviewer qualityReviewer;
    private final AggregationAgent aggregationAgent;
    private final ExecutorService parallelExecutor;
    private final ExecutorManager executorManager;
    private final PipelineDiffHelper diffHelper = new PipelineDiffHelper();
    private final PipelineResultConverter resultConverter = new PipelineResultConverter();
    private final AtomicInteger totalTokensUsed = new AtomicInteger(0);

    /**
     * 带 Tool Use 的构造方法。
     */
    public MultiStageReviewService(ChatModel chatModel, FileAccessSandbox sandbox) {
        this.summaryAgent = AiServices.create(DiffSummaryAgent.class, chatModel);
        UnifiedToolProvider tools = new UnifiedToolProvider(sandbox.getProjectRoot(), List.of(), sandbox, 10);
        this.securityReviewer = AiServices.builder(SecurityReviewer.class)
                .chatModel(chatModel).tools(tools).build();
        this.logicReviewer = AiServices.builder(LogicReviewer.class)
                .chatModel(chatModel).tools(tools).build();
        this.qualityReviewer = AiServices.builder(QualityReviewer.class)
                .chatModel(chatModel).tools(tools).build();
        this.aggregationAgent = AiServices.create(AggregationAgent.class, chatModel);
        this.executorManager = new ExecutorManager();
        this.parallelExecutor = executorManager.createFixedPool(3, "pipeline-review");
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
        this.executorManager = new ExecutorManager();
        this.parallelExecutor = executorManager.createFixedPool(3, "pipeline-review");
    }

    @Override
    public ReviewResult review(List<DiffFileEntry> diffEntries, Path projectDir) throws com.diffguard.exception.DiffGuardException {
        return review(diffEntries, projectDir, DEFAULT_MAX_TOTAL_TOKENS, null);
    }

    /**
     * 执行多阶段审查 Pipeline（带 token 限制和 provider 信息）。
     */
    public ReviewResult review(List<DiffFileEntry> diffEntries, Path projectDir,
                               int maxTotalTokens, String provider) {
        long startTime = System.currentTimeMillis();
        String allDiffs = prepareDiffs(diffEntries, maxTotalTokens, provider);

        // Stage 1: 总结变更
        DiffSummary summary = runStage1(allDiffs);
        String summaryText = summary != null && summary.summary() != null
                ? summary.summary() : "代码变更审查";

        // Stage 2: 并行专项审查
        ParallelReviewResult parallelResult = runStage2(summaryText, allDiffs);

        // Stage 3: 聚合
        AggregatedReview aggregated = runStage3(summaryText, parallelResult);

        // 转换为 ReviewResult
        ReviewResult result = resultConverter.convert(aggregated, diffEntries.size());
        result.setReviewDurationMs(System.currentTimeMillis() - startTime);
        log.info("[Pipeline] 完成：{} 个问题，耗时 {}ms",
                result.getIssues().size(), result.getReviewDurationMs());
        return result;
    }

    private String prepareDiffs(List<DiffFileEntry> diffEntries, int maxTotalTokens, String provider) {
        String rawDiffs = diffHelper.concatenateDiffs(diffEntries);
        if (maxTotalTokens > 0 && provider != null) {
            int estimatedTokens = TokenEstimator.estimate(rawDiffs, provider);
            if (estimatedTokens > maxTotalTokens) {
                log.warn("[Pipeline] Diff token 估算 {} 超过限制 {}，截断处理",
                        estimatedTokens, maxTotalTokens);
                return diffHelper.truncateToTokenLimit(rawDiffs, maxTotalTokens, provider);
            }
        }
        return rawDiffs;
    }

    private DiffSummary runStage1(String allDiffs) {
        log.info("[Pipeline] Stage 1: 分析变更摘要...");
        long start = System.currentTimeMillis();
        try {
            Result<DiffSummary> result = summaryAgent.summarize(allDiffs);
            trackTokens(result);
            log.info("[Pipeline] Stage 1 完成：耗时 {}ms", System.currentTimeMillis() - start);
            return result.content();
        } catch (Exception e) {
            log.warn("[Pipeline] Stage 1 失败，使用简化摘要：{}", e.getMessage());
            return new DiffSummary("代码变更审查", List.of(), List.of(), 3);
        }
    }

    private record ParallelReviewResult(String security, String logic, String quality) {}

    private ParallelReviewResult runStage2(String summaryText, String allDiffs) {
        log.info("[Pipeline] Stage 2: 并行专项审查（安全/逻辑/质量）...");
        long start = System.currentTimeMillis();

        CompletableFuture<String> securityFuture = CompletableFuture.supplyAsync(
                () -> safeReview(securityReviewer, summaryText, allDiffs), parallelExecutor);
        CompletableFuture<String> logicFuture = CompletableFuture.supplyAsync(
                () -> safeReview(logicReviewer, summaryText, allDiffs), parallelExecutor);
        CompletableFuture<String> qualityFuture = CompletableFuture.supplyAsync(
                () -> safeReview(qualityReviewer, summaryText, allDiffs), parallelExecutor);

        String securityResult = null, logicResult = null, qualityResult = null;
        try {
            CompletableFuture.allOf(securityFuture, logicFuture, qualityFuture)
                    .get(5, TimeUnit.MINUTES);
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

        log.info("[Pipeline] Stage 2 完成：耗时 {}ms", System.currentTimeMillis() - start);
        return new ParallelReviewResult(
                safeGetResult(securityFuture, securityResult),
                safeGetResult(logicFuture, logicResult),
                safeGetResult(qualityFuture, qualityResult));
    }

    private AggregatedReview runStage3(String summaryText, ParallelReviewResult pr) {
        log.info("[Pipeline] Stage 3: 聚合审查结果...");
        long start = System.currentTimeMillis();
        try {
            String secInput = pr.security() != null ? pr.security() : "（安全审查未返回结果）";
            String logInput = pr.logic() != null ? pr.logic() : "（逻辑审查未返回结果）";
            String qualInput = pr.quality() != null ? pr.quality() : "（质量审查未返回结果）";
            Result<AggregatedReview> aggResult = aggregationAgent.aggregate(
                    summaryText, secInput, logInput, qualInput);
            trackTokens(aggResult);
            log.info("[Pipeline] Stage 3 完成：耗时 {}ms", System.currentTimeMillis() - start);
            return aggResult.content();
        } catch (Exception e) {
            log.warn("[Pipeline] Stage 3 聚合失败，手动合并：{}", e.getMessage());
            return null;
        }
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
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Override
    public void close() {
        executorManager.close();
    }
}
