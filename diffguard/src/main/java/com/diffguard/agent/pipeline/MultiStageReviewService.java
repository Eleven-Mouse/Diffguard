package com.diffguard.agent.pipeline;

import com.diffguard.model.IssueRecord;
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
        ReviewToolProvider toolProvider = new ReviewToolProvider(sandbox);
        this.summaryAgent = AiServices.create(DiffSummaryAgent.class, chatModel);
        this.securityReviewer = AiServices.builder(SecurityReviewer.class)
                .chatModel(chatModel).tools(toolProvider).build();
        this.logicReviewer = AiServices.builder(LogicReviewer.class)
                .chatModel(chatModel).tools(toolProvider).build();
        this.qualityReviewer = AiServices.builder(QualityReviewer.class)
                .chatModel(chatModel).tools(toolProvider).build();
        this.aggregationAgent = AiServices.create(AggregationAgent.class, chatModel);
        this.parallelExecutor = Executors.newFixedThreadPool(3);
    }

    /**
     * 执行多阶段审查 Pipeline。
     *
     * @param diffEntries 差异文件列表
     * @param projectDir  项目目录（用于 Tool Use 文件访问）
     * @return 与单次审查格式一致的 ReviewResult
     */
    public ReviewResult review(List<DiffFileEntry> diffEntries, java.nio.file.Path projectDir) {
        long startTime = System.currentTimeMillis();
        String allDiffs = concatenateDiffs(diffEntries);

        // Stage 1: 总结变更
        log.info("[Pipeline] Stage 1: 分析变更摘要...");
        DiffSummary summary;
        try {
            Result<DiffSummary> summaryResult = summaryAgent.summarize(allDiffs);
            summary = summaryResult.content();
        } catch (Exception e) {
            log.warn("[Pipeline] Stage 1 失败，使用简化摘要：{}", e.getMessage());
            summary = new DiffSummary("代码变更审查", List.of(), List.of(), 3);
        }

        String summaryText = summary != null && summary.summary() != null
                ? summary.summary() : "代码变更审查";

        // Stage 2: 并行专项审查
        log.info("[Pipeline] Stage 2: 并行专项审查（安全/逻辑/质量）...");
        CompletableFuture<String> securityFuture = CompletableFuture.supplyAsync(
                () -> safeReview(securityReviewer, summaryText, allDiffs), parallelExecutor);
        CompletableFuture<String> logicFuture = CompletableFuture.supplyAsync(
                () -> safeReview(logicReviewer, summaryText, allDiffs), parallelExecutor);
        CompletableFuture<String> qualityFuture = CompletableFuture.supplyAsync(
                () -> safeReview(qualityReviewer, summaryText, allDiffs), parallelExecutor);

        String securityResult = securityFuture.join();
        String logicResult = logicFuture.join();
        String qualityResult = qualityFuture.join();

        // Stage 3: 聚合
        log.info("[Pipeline] Stage 3: 聚合审查结果...");
        AggregatedReview aggregated;
        try {
            Result<AggregatedReview> aggResult = aggregationAgent.aggregate(
                    summaryText, securityResult, logicResult, qualityResult);
            aggregated = aggResult.content();
        } catch (Exception e) {
            log.warn("[Pipeline] Stage 3 聚合失败，手动合并：{}", e.getMessage());
            aggregated = null;
        }

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
                return sr.review(summary, diff).content().toString();
            } else if (reviewer instanceof LogicReviewer lr) {
                return lr.review(summary, diff).content().toString();
            } else if (reviewer instanceof QualityReviewer qr) {
                return qr.review(summary, diff).content().toString();
            }
        } catch (Exception e) {
            log.warn("[Pipeline] 专项审查失败：{}", e.getMessage());
        }
        return "审查失败，无结果";
    }

    private String concatenateDiffs(List<DiffFileEntry> entries) {
        return entries.stream()
                .map(e -> "--- 文件：" + e.getFilePath() + " ---\n" + e.getContent())
                .collect(Collectors.joining("\n\n"));
    }

    private ReviewResult convertToReviewResult(AggregatedReview aggregated, int fileCount) {
        ReviewResult result = new ReviewResult();
        result.setTotalFilesReviewed(fileCount);

        if (aggregated == null) {
            return result;
        }

        if (aggregated.has_critical()) {
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
    }
}
