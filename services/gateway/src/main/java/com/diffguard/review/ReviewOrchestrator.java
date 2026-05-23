package com.diffguard.review;

import com.diffguard.platform.config.ReviewConfig;
import com.diffguard.platform.git.DiffCollector;
import com.diffguard.review.model.DiffFileEntry;
import com.diffguard.review.model.ReviewResult;
import com.diffguard.platform.observability.MetricsService;
import com.diffguard.platform.output.MarkdownFormatter;
import com.diffguard.platform.output.ProgressDisplay;
import com.diffguard.webhook.GitHubApiClient;
import com.diffguard.webhook.GitHubPayloadParser;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Webhook 触发的代码审查编排器。
 * <p>
 * 接收 PR 信息后异步执行审查管线：
 * git fetch → collectDiff → ReviewExecutionAdapter(remote/legacy) → format → postComment
 * <p>
 * 说明：AST enrich / 规则扫描 / 引擎分发已下沉到 orchestrator service（或其兼容回退路径）。
 */
public class ReviewOrchestrator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private static final long TASK_TIMEOUT_SECONDS = 300;

    private final ReviewConfig config;
    private final ExecutorService executor;
    private final ScheduledExecutorService timeoutExecutor;
    private GitHubApiClient githubClient;
    private final ReviewExecutionAdapter reviewExecutionAdapter;

    // 指标组件（nullable，按可用性降级）
    private final MetricsService metrics;

    public ReviewOrchestrator(ReviewConfig config) {
        this(config, null, null);
    }

    ReviewOrchestrator(ReviewConfig config, GitHubApiClient githubClient) {
        this(config, githubClient, null);
    }

    ReviewOrchestrator(ReviewConfig config,
                       GitHubApiClient githubClient,
                       ReviewExecutionAdapter reviewExecutionAdapter) {
        this.config = config;
        this.executor = new ThreadPoolExecutor(
                1, 4, 60L, SECONDS,
                new LinkedBlockingQueue<>(10),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        this.githubClient = githubClient != null ? githubClient : new GitHubApiClient(config);
        this.reviewExecutionAdapter = reviewExecutionAdapter != null
                ? reviewExecutionAdapter
                : new ReviewExecutionAdapter(config);

        this.metrics = initMetrics();
    }

    /**
     * 异步处理 PR 审查任务。
     */
    public void processAsync(GitHubPayloadParser.ParsedPullRequest pr) {
        if (pr == null) {
            log.warn("忽略空的 PR payload");
            return;
        }
        Future<?> future = executor.submit(() -> {
            try {
                processInternal(pr);
            } catch (Exception e) {
                log.error("审查失败 {}/pull/{}: {}", pr.getRepoFullName(), pr.getPrNumber(), e.getMessage(), e);
                postErrorComment(pr, e);
                if (metrics != null) metrics.recordReviewFailed();
            }
        });

        timeoutExecutor.schedule(() -> {
            if (!future.isDone()) {
                future.cancel(true);
                log.warn("审查超时 {}/pull/{}，已取消（{}s）", pr.getRepoFullName(), pr.getPrNumber(), TASK_TIMEOUT_SECONDS);
                postErrorComment(pr, new RuntimeException("审查超时（" + TASK_TIMEOUT_SECONDS + "s）"));
                if (metrics != null) metrics.recordReviewFailed();
            }
        }, TASK_TIMEOUT_SECONDS, SECONDS);
    }

    private void processInternal(GitHubPayloadParser.ParsedPullRequest pr) throws Exception {
        Timer.Sample timerSample = metrics != null ? metrics.startReviewTimer() : null;
        if (metrics != null) metrics.recordReviewSubmitted();

        // 1. 查找本地仓库路径
        if (config.getWebhook() == null) {
            throw new IllegalStateException("webhook config missing");
        }
        Path localPath = config.getWebhook().resolveLocalPath(pr.getRepoFullName());
        if (localPath == null) {
            log.warn("未配置仓库的本地路径：{}", pr.getRepoFullName());
            return;
        }

        // 2. git fetch
        runGitFetch(localPath, pr.getHeadRef());

        ProgressDisplay.setSilent(true);
        try {
            // 3. 收集 diff
            List<DiffFileEntry> diffEntries = DiffCollector.collectDiffBetweenRefs(
                    localPath, pr.getBaseRef(), pr.getHeadSha(), config);
            if (diffEntries.isEmpty()) {
                log.info("无差异条目：{}/pull/{}", pr.getRepoFullName(), pr.getPrNumber());
                return;
            }

            // 4. 执行审查（remote orchestrator / legacy local engine）
            ReviewEngineFactory.EngineType engineType =
                    ReviewEngineFactory.resolveEngineType(config, false, false);

            ReviewResult result = reviewExecutionAdapter.review(localPath, diffEntries, engineType, false);
            if (result == null) {
                throw new RuntimeException("review execution returned null");
            }

            // 5. 记录指标
            if (metrics != null) {
                metrics.recordReviewSuccess();
                metrics.recordIssues(result.getIssues().size());
                metrics.recordTokensUsed(result.getTotalTokensUsed());
                if (timerSample != null) metrics.stopReviewTimer(timerSample);
            }

            // 6. 格式化 + 发布 PR 评论
            String markdown = MarkdownFormatter.format(result);
            githubClient.postComment(pr.getRepoFullName(), pr.getPrNumber(), markdown);

            log.info("审查完成：{}/pull/{} - {} 个问题",
                    pr.getRepoFullName(), pr.getPrNumber(), result.getIssues().size());

        } finally {
            ProgressDisplay.setSilent(false);
        }
    }

    private void runGitFetch(Path projectDir, String ref) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "fetch", "origin", ref);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(30, SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("git fetch 超时（30s）");
        }
        if (process.exitValue() != 0) {
            log.warn("git fetch 返回非零退出码：{}", process.exitValue());
        }
    }

    private void postErrorComment(GitHubPayloadParser.ParsedPullRequest pr, Exception e) {
        try {
            String errorMd = "## DiffGuard Review Failed\n\n"
                    + "Code review encountered an error:\n\n"
                    + "> " + e.getMessage() + "\n\n"
                    + "_Please check the server logs for details._";
            githubClient.postComment(pr.getRepoFullName(), pr.getPrNumber(), errorMd);
        } catch (Exception postError) {
            log.error("发布错误评论失败：{}", postError.getMessage());
        }
    }

    // --- 初始化辅助 ---

    private MetricsService initMetrics() {
        try {
            return new MetricsService();
        } catch (Exception e) {
            log.warn("MetricsService init failed: {}", e.getMessage());
            return null;
        }
    }

    public MetricsService getMetrics() { return metrics; }

    @Override
    public void close() {
        executor.shutdown();
        timeoutExecutor.shutdown();
        try {
            if (!executor.awaitTermination(10, SECONDS)) {
                executor.shutdownNow();
                log.warn("ReviewOrchestrator 线程池未在 10s 内优雅关闭，已强制终止");
            }
            if (!timeoutExecutor.awaitTermination(5, SECONDS)) {
                timeoutExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            timeoutExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
