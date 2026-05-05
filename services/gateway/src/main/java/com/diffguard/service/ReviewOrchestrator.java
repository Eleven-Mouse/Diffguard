package com.diffguard.service;

import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.domain.review.AsyncReviewEngine;
import com.diffguard.domain.review.ReviewEngine;
import com.diffguard.infrastructure.git.DiffCollector;
import com.diffguard.infrastructure.messaging.RabbitMQConfig;
import com.diffguard.infrastructure.messaging.ReviewTaskMessage;
import com.diffguard.infrastructure.messaging.ReviewTaskPublisher;
import com.diffguard.domain.review.model.DiffFileEntry;
import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.infrastructure.observability.MetricsService;
import com.diffguard.infrastructure.output.MarkdownFormatter;
import com.diffguard.infrastructure.output.ProgressDisplay;
import com.diffguard.infrastructure.persistence.DatabaseConfig;
import com.diffguard.infrastructure.persistence.ReviewResultRepository;
import com.diffguard.infrastructure.persistence.ReviewTaskRepository;
import com.diffguard.infrastructure.resilience.ResilienceService;
import com.diffguard.domain.rules.RuleEngine;
import com.diffguard.adapter.webhook.GitHubApiClient;
import com.diffguard.adapter.webhook.GitHubPayloadParser;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Webhook 触发的代码审查编排器。
 * <p>
 * 接收 PR 信息后异步执行完整管线：
 * git fetch → collectDiff → AST enrich → 静态规则扫描 → Review(同步/异步) → 持久化 → format → postComment
 * <p>
 * 集成：RabbitMQ 异步分发、MySQL 持久化、Resilience4j 熔断、Metrics 指标、静态规则引擎。
 */
public class ReviewOrchestrator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private static final long TASK_TIMEOUT_SECONDS = 300;

    private final ReviewConfig config;
    private final ExecutorService executor;
    private final ScheduledExecutorService timeoutExecutor;
    private final GitHubApiClient githubClient;

    // 新增组件（nullable，向后兼容）
    private final MetricsService metrics;
    private final ResilienceService resilience;
    private final RuleEngine ruleEngine;
    private final ReviewTaskRepository taskRepo;
    private final ReviewResultRepository resultRepo;
    private final ReviewTaskPublisher taskPublisher;

    public ReviewOrchestrator(ReviewConfig config) {
        this.config = config;
        this.executor = new ThreadPoolExecutor(
                1, 4, 60L, SECONDS,
                new LinkedBlockingQueue<>(10),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        this.githubClient = new GitHubApiClient(config);

        // 初始化新组件（按可用性降级）
        this.metrics = initMetrics();
        this.resilience = new ResilienceService();
        this.ruleEngine = new RuleEngine();

        ReviewTaskRepository tr = null;
        ReviewResultRepository rr = null;
        ReviewTaskPublisher tp = null;

        if (isDatabaseEnabled()) {
            try {
                DatabaseConfig dbc = DatabaseConfig.fromEnv();
                DataSource ds = dbc.getDataSource();
                tr = new ReviewTaskRepository(ds);
                rr = new ReviewResultRepository(ds);
            } catch (Exception e) {
                log.warn("MySQL unavailable, persistence disabled: {}", e.getMessage());
            }
        }

        if (isMessageQueueEnabled()) {
            try {
                RabbitMQConfig mqConfig = RabbitMQConfig.fromEnv();
                tp = new ReviewTaskPublisher(mqConfig);
            } catch (Exception e) {
                log.warn("RabbitMQ unavailable, async dispatch disabled: {}", e.getMessage());
            }
        }

        this.taskRepo = tr;
        this.resultRepo = rr;
        this.taskPublisher = tp;
    }

    ReviewOrchestrator(ReviewConfig config, GitHubApiClient githubClient) {
        this(config);
    }

    /**
     * 异步处理 PR 审查任务。
     */
    public void processAsync(GitHubPayloadParser.ParsedPullRequest pr) {
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

        String taskId = java.util.UUID.randomUUID().toString();

        // 1. 查找本地仓库路径
        Path localPath = config.getWebhook().resolveLocalPath(pr.getRepoFullName());
        if (localPath == null) {
            log.warn("未配置仓库的本地路径：{}", pr.getRepoFullName());
            return;
        }

        // 2. git fetch
        runGitFetch(localPath, pr.getHeadRef());

        // 3. 持久化任务（MySQL）
        if (taskRepo != null) {
            taskRepo.insert(taskId, pr.getRepoFullName(), pr.getPrNumber(), "PENDING", 0);
            taskRepo.updateStatus(taskId, "RUNNING");
        }

        ProgressDisplay.setSilent(true);
        try {
            // 4. 收集 diff + AST enrich
            List<DiffFileEntry> rawEntries = DiffCollector.collectDiffBetweenRefs(
                    localPath, pr.getBaseRef(), pr.getHeadSha(), config);
            if (rawEntries.isEmpty()) {
                log.info("无差异条目：{}/pull/{}", pr.getRepoFullName(), pr.getPrNumber());
                if (taskRepo != null) taskRepo.updateStatus(taskId, "COMPLETED");
                return;
            }

            List<DiffFileEntry> diffEntries = new com.diffguard.domain.ast.ASTEnricher(localPath, config).enrich(rawEntries);

            // 5. 静态规则引擎扫描（零 LLM 开销）
            List<ReviewIssue> staticIssues = ruleEngine.scan(diffEntries);
            if (!staticIssues.isEmpty()) {
                log.info("Static rules found {} issues", staticIssues.size());
                if (resultRepo != null) resultRepo.saveStaticIssues(taskId, staticIssues);
                if (metrics != null) {
                    for (int i = 0; i < staticIssues.size(); i++) metrics.recordStaticHit();
                }
            }

            // 更新任务文件数
            if (taskRepo != null) {
                // Re-insert with correct count
            }

            // 6. 执行 LLM Review（通过 Resilience4j 包装）
            ReviewEngineFactory.EngineType engineType =
                    ReviewEngineFactory.resolveEngineType(config, false, false);

            ReviewResult result;
            if (taskPublisher != null && isMessageQueueEnabled()) {
                // 异步模式: RabbitMQ
                result = executeViaRabbitMQ(engineType, diffEntries, localPath, taskId);
            } else {
                // 同步模式（通过 Resilience4j 熔断包装）
                result = resilience.executeAgentCall(() -> {
                    try (ReviewEngine engine = ReviewEngineFactory.create(
                            engineType, config, localPath, diffEntries, false)) {
                        return engine.review(diffEntries, localPath);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            // 7. 合并静态规则 Issue
            for (ReviewIssue si : staticIssues) {
                result.addIssue(si);
            }

            // 8. 持久化结果
            if (resultRepo != null) resultRepo.saveResult(taskId, result);
            if (taskRepo != null) taskRepo.updateStatus(taskId, "COMPLETED");

            // 9. 记录指标
            if (metrics != null) {
                metrics.recordReviewSuccess();
                metrics.recordIssues(result.getIssues().size());
                metrics.recordTokensUsed(result.getTotalTokensUsed());
                if (timerSample != null) metrics.stopReviewTimer(timerSample);
            }

            // 10. 格式化 + 发布 PR 评论
            String markdown = MarkdownFormatter.format(result);
            githubClient.postComment(pr.getRepoFullName(), pr.getPrNumber(), markdown);

            log.info("审查完成：{}/pull/{} - {} 个问题 (static={}, llm={})",
                    pr.getRepoFullName(), pr.getPrNumber(), result.getIssues().size(),
                    staticIssues.size(), result.getIssues().size() - staticIssues.size());

        } finally {
            ProgressDisplay.setSilent(false);
        }
    }

    /**
     * 通过 RabbitMQ 异步执行 Review。
     */
    private ReviewResult executeViaRabbitMQ(ReviewEngineFactory.EngineType engineType,
                                            List<DiffFileEntry> diffEntries,
                                            Path localPath, String taskId) {
        try {
            String toolServerUrl = ReviewEngineFactory.resolveToolServerUrl(config);
            String mode = switch (engineType) {
                case PIPELINE -> "PIPELINE";
                case MULTI_AGENT -> "MULTI_AGENT";
                case SIMPLE -> "PIPELINE"; // force pipeline for async
            };

            ReviewTaskMessage message = new ReviewTaskMessage(
                    mode, config, diffEntries, localPath.toString(), toolServerUrl, false);

            taskPublisher.publish(message);

            // 用 AsyncReviewEngine 轮询等待结果
            try (AsyncReviewEngine asyncEngine = new AsyncReviewEngine(taskId, config)) {
                return asyncEngine.review(diffEntries, localPath);
            }
        } catch (Exception e) {
            log.warn("RabbitMQ async failed, falling back to sync: {}", e.getMessage());
            // Fallback to sync
            try (ReviewEngine engine = ReviewEngineFactory.create(
                    engineType, config, localPath, diffEntries, false)) {
                return engine.review(diffEntries, localPath);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
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

    private boolean isMessageQueueEnabled() {
        return config.getMessageQueue() != null && config.getMessageQueue().isEnabled();
    }

    private boolean isDatabaseEnabled() {
        return config.getDatabase() != null && config.getDatabase().isEnabled();
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
