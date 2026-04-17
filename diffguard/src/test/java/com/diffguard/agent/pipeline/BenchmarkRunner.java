package com.diffguard.agent.pipeline;

import com.diffguard.agent.pipeline.model.AggregatedReview;
import com.diffguard.agent.pipeline.model.DiffSummary;
import com.diffguard.llm.provider.LlmProvider;
import com.diffguard.model.*;
import com.diffguard.output.ConsoleFormatter;
import com.diffguard.output.MarkdownFormatter;
import dev.langchain4j.service.Result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DiffGuard 性能基准测试。
 * 对比单次审查 vs 多阶段 Pipeline 的延迟和 Token 开销。
 * <p>
 * 运行方式：mvn -Pbenchmark exec:java
 */
public class BenchmarkRunner {

    // --- 数据生成 ---

    static ReviewResult createRealisticResult(int fileCount, int issueCount, boolean hasCritical) {
        ReviewResult result = new ReviewResult();
        for (int i = 0; i < issueCount; i++) {
            ReviewIssue issue = new ReviewIssue();
            Severity severity;
            if (hasCritical && i < issueCount / 5) {
                severity = Severity.CRITICAL;
            } else if (i < issueCount / 2) {
                severity = Severity.WARNING;
            } else {
                severity = Severity.INFO;
            }
            issue.setSeverity(severity);
            issue.setFile("src/main/java/com/example/Service" + (i % fileCount) + ".java");
            issue.setLine(i * 10 + 1);
            issue.setType(switch (severity) {
                case CRITICAL -> "安全漏洞";
                case WARNING -> "代码质量";
                case INFO -> "代码亮点";
            });
            issue.setMessage("问题描述：这是一个测试用的审查问题 #" + i);
            issue.setSuggestion("建议：修复方案描述 #" + i);
            result.addIssue(issue);
        }
        if (hasCritical) {
            result.setHasCriticalFlag(true);
        }
        result.setTotalFilesReviewed(fileCount);
        result.setTotalTokensUsed(fileCount * 1200);
        result.setReviewDurationMs(2500);
        return result;
    }

    static List<DiffFileEntry> createDiffEntries(int count) {
        List<DiffFileEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StringBuilder diff = new StringBuilder();
            for (int j = 0; j < 30; j++) {
                diff.append("@@ -").append(j * 10).append(",5 +").append(j * 10).append(",5 @@\n");
                diff.append("-old line ").append(j).append("\n");
                diff.append("+new line ").append(j).append(" with changes\n");
            }
            entries.add(new DiffFileEntry(
                    "src/main/java/com/example/Service" + i + ".java",
                    diff.toString(), 500));
        }
        return entries;
    }

    // --- 基准测试 1：输出格式化延迟 ---

    static void benchmarkOutputFormatting(ReviewResult result) {
        int warmup = 100;
        int iterations = 2000;

        for (int i = 0; i < warmup; i++) {
            ConsoleFormatter.printReport(result);
        }

        long consoleStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            ConsoleFormatter.printReport(result);
        }
        long consoleMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - consoleStart);

        for (int i = 0; i < warmup; i++) {
            MarkdownFormatter.format(result);
        }

        long mdStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            MarkdownFormatter.format(result);
        }
        long mdMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - mdStart);

        System.out.printf("  ConsoleFormatter: %d iterations in %dms (avg %.2fms)%n",
                iterations, consoleMs, (double) consoleMs / iterations);
        System.out.printf("  MarkdownFormatter: %d iterations in %dms (avg %.2fms)%n",
                iterations, mdMs, (double) mdMs / iterations);
    }

    // --- 基准测试 2：单次审查 vs Pipeline 延迟 ---

    static long[] benchmarkSingleReview(int delayMs) throws Exception {
        LlmProvider mockProvider = new LlmProvider() {
            @Override
            public String call(String systemPrompt, String userPrompt) throws java.io.IOException {
                sleepSafely(delayMs);
                return cannedJsonResponse();
            }
        };

        int runs = 5;
        long[] times = new long[runs];
        for (int r = 0; r < runs; r++) {
            long start = System.nanoTime();
            mockProvider.call("system", "user");
            times[r] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }
        return times;
    }

    static long[] benchmarkPipeline(
            DiffSummaryAgent summaryAgent,
            SecurityReviewer securityReviewer,
            LogicReviewer logicReviewer,
            QualityReviewer qualityReviewer,
            AggregationAgent aggregationAgent,
            List<DiffFileEntry> entries,
            Path tempDir) throws Exception {

        int runs = 5;
        long[] times = new long[runs];
        for (int r = 0; r < runs; r++) {
            try (MultiStageReviewService pipeline = new MultiStageReviewService(
                    summaryAgent, securityReviewer, logicReviewer, qualityReviewer, aggregationAgent)) {
                long start = System.nanoTime();
                pipeline.review(entries, tempDir);
                times[r] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            }
        }
        return times;
    }

    // --- 报告生成 ---

    static void writeMarkdownReport(long[] singleTimes, long[] pipelineTimes,
                                     ReviewResult realisticResult,
                                     Path outputFile) throws IOException {
        long singleAvg = average(singleTimes);
        long singleMin = min(singleTimes);
        long singleMax = max(singleTimes);
        long pipelineAvg = average(pipelineTimes);
        long pipelineMin = min(pipelineTimes);
        long pipelineMax = max(pipelineTimes);

        int singleTokenBudget = 4000;
        int pipelineTokenBudget = 4000 + 3 * 3000 + 2000;

        StringBuilder sb = new StringBuilder();
        sb.append("# DiffGuard 性能基准测试\n\n");
        sb.append("测试环境：模拟 LLM 调用延迟（单次 ~2s，Pipeline 各阶段 ~1.5s/2s/1s）\n\n");

        sb.append("## 1. 审查模式延迟对比\n\n");
        sb.append("| 模式 | 平均延迟 (ms) | 最小 (ms) | 最大 (ms) | Token 预算 (估) |\n");
        sb.append("|------|-------------|----------|----------|----------------|\n");
        sb.append(String.format("| 单次审查 | %d | %d | %d | ~%d |\n", singleAvg, singleMin, singleMax, singleTokenBudget));
        sb.append(String.format("| Pipeline（3阶段） | %d | %d | %d | ~%d |\n", pipelineAvg, pipelineMin, pipelineMax, pipelineTokenBudget));
        sb.append(String.format("\nPipeline 延迟倍率：%.1fx\n", (double) pipelineAvg / singleAvg));
        sb.append(String.format("Pipeline Token 开销倍率：%.1fx\n", (double) pipelineTokenBudget / singleTokenBudget));

        sb.append("\n## 2. 准确率权衡\n\n");
        sb.append("| 维度 | 单次审查 | Pipeline（3阶段） |\n");
        sb.append("|------|---------|------------------|\n");
        sb.append("| 安全专项分析 | 混合在通用审查中 | 独立专项审查（SQL注入、XSS、硬编码密钥） |\n");
        sb.append("| 逻辑/Bug 检测 | 混合在通用审查中 | 独立专项审查（空指针、并发、资源泄漏） |\n");
        sb.append("| 代码质量评审 | 混合在通用审查中 | 独立专项审查（命名、复杂度、可维护性） |\n");
        sb.append("| 结果去重 | 无 | 有（Stage 3 聚合去重） |\n");
        sb.append("| Token 开销 | 基准（~4k） | ~3.25x（~13k） |\n");
        sb.append("| 延迟开销 | 基准 | ~1.5-2.5x（并行缓解） |\n");
        sb.append("| 适用场景 | 快速审查、小 diff | 重要提交、大 diff、安全敏感代码 |\n");

        sb.append("\n## 3. 测试数据\n\n");
        sb.append(String.format("- 文件数：%d\n", realisticResult.getTotalFilesReviewed()));
        sb.append(String.format("- 问题数：%d（CRITICAL: %d, WARNING: %d, INFO: %d）\n",
                realisticResult.getIssues().size(),
                realisticResult.getIssuesBySeverity(Severity.CRITICAL).size(),
                realisticResult.getIssuesBySeverity(Severity.WARNING).size(),
                realisticResult.getIssuesBySeverity(Severity.INFO).size()));
        sb.append(String.format("- Token 消耗：%d\n", realisticResult.getTotalTokensUsed()));

        Files.writeString(outputFile, sb.toString());
        System.out.println("\n报告已生成：" + outputFile.toAbsolutePath());
    }

    // --- 工具方法 ---

    private static void sleepSafely(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static long average(long[] arr) {
        long sum = 0;
        for (long v : arr) sum += v;
        return sum / arr.length;
    }

    static long min(long[] arr) {
        long m = Long.MAX_VALUE;
        for (long v : arr) if (v < m) m = v;
        return m;
    }

    static long max(long[] arr) {
        long m = Long.MIN_VALUE;
        for (long v : arr) if (v > m) m = v;
        return m;
    }

    // --- Mock 工厂 ---

    static String cannedJsonResponse() {
        return """
                {
                  "has_critical": false,
                  "summary": "代码审查结果",
                  "issues": [
                    {"severity": "WARNING", "file": "Service.java", "line": 10, "type": "代码质量", "message": "建议优化", "suggestion": "使用更好的方法"},
                    {"severity": "INFO", "file": "Util.java", "line": 5, "type": "代码风格", "message": "命名清晰", "suggestion": null}
                  ],
                  "highlights": ["结构良好"],
                  "test_suggestions": ["增加边界测试"]
                }
                """;
    }

    static DiffSummaryAgent mockSummaryAgent(int delayMs) {
        return new DiffSummaryAgent() {
            @Override
            public Result<DiffSummary> summarize(String diffContent) {
                sleepSafely(delayMs);
                return Result.<DiffSummary>builder()
                        .content(new DiffSummary("变更总结", List.of("Service.java"), List.of("refactor"), 2))
                        .build();
            }
        };
    }

    static SecurityReviewer mockSecurityReviewer(int delayMs) {
        return new SecurityReviewer() {
            @Override
            public Result<TargetedReviewResult> review(String summary, String diffContent) {
                sleepSafely(delayMs);
                return Result.<TargetedReviewResult>builder()
                        .content(new TargetedReviewResult("安全审查通过", List.of()))
                        .build();
            }
        };
    }

    static LogicReviewer mockLogicReviewer(int delayMs) {
        return new LogicReviewer() {
            @Override
            public Result<TargetedReviewResult> review(String summary, String diffContent) {
                sleepSafely(delayMs);
                return Result.<TargetedReviewResult>builder()
                        .content(new TargetedReviewResult("逻辑审查通过", List.of(
                                new IssueRecord("WARNING", "Service.java", 10, "逻辑", "潜在空指针", "添加 null 检查")
                        )))
                        .build();
            }
        };
    }

    static QualityReviewer mockQualityReviewer(int delayMs) {
        return new QualityReviewer() {
            @Override
            public Result<TargetedReviewResult> review(String summary, String diffContent) {
                sleepSafely(delayMs);
                return Result.<TargetedReviewResult>builder()
                        .content(new TargetedReviewResult("质量审查通过", List.of()))
                        .build();
            }
        };
    }

    static AggregationAgent mockAggregationAgent(int delayMs) {
        return new AggregationAgent() {
            @Override
            public Result<AggregatedReview> aggregate(String summary, String securityResult, String logicResult, String qualityResult) {
                sleepSafely(delayMs);
                return Result.<AggregatedReview>builder()
                        .content(new AggregatedReview(false, "综合总结",
                                List.of(new IssueRecord("WARNING", "Service.java", 10, "逻辑", "潜在空指针", "添加 null 检查")),
                                List.of(), List.of()))
                        .build();
            }
        };
    }

    // --- Main ---

    public static void main(String[] args) throws Exception {
        System.out.println("=== DiffGuard Performance Benchmark ===\n");

        List<DiffFileEntry> entries = createDiffEntries(5);
        ReviewResult realisticResult = createRealisticResult(5, 25, true);
        Path tempDir = Files.createTempDirectory("diffguard-bench");
        Path reportPath = Path.of("benchmark-results.md");

        System.out.println("--- 1. Output Formatting Latency ---");
        benchmarkOutputFormatting(realisticResult);

        System.out.println("\n--- 2. Single-Review vs Pipeline Latency ---");

        System.out.println("Running single-review benchmark...");
        long[] singleTimes = benchmarkSingleReview(2000);
        System.out.printf("  Single-review: avg=%dms, min=%dms, max=%dms%n",
                average(singleTimes), min(singleTimes), max(singleTimes));

        System.out.println("Running pipeline benchmark...");
        long[] pipelineTimes = benchmarkPipeline(
                mockSummaryAgent(1500),
                mockSecurityReviewer(2000),
                mockLogicReviewer(2000),
                mockQualityReviewer(2000),
                mockAggregationAgent(1000),
                entries, tempDir);
        System.out.printf("  Pipeline: avg=%dms, min=%dms, max=%dms%n",
                average(pipelineTimes), min(pipelineTimes), max(pipelineTimes));

        System.out.println("\n--- 3. Generating Report ---");
        writeMarkdownReport(singleTimes, pipelineTimes, realisticResult, reportPath);

        Files.deleteIfExists(tempDir);
        System.out.println("\n=== Benchmark Complete ===");
    }
}
