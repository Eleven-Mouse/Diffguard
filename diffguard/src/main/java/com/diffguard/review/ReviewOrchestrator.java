package com.diffguard.review;

import com.diffguard.config.ReviewConfig;
import com.diffguard.git.DiffCollector;
import com.diffguard.webhook.GitHubApiClient;
import com.diffguard.webhook.GitHubPayloadParser;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.ReviewResult;
import com.diffguard.output.MarkdownFormatter;
import com.diffguard.output.ProgressDisplay;
import com.diffguard.review.ReviewEngine;
import com.diffguard.review.ReviewEngineFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Webhook 触发的代码审查编排器。
 * 接收 PR 信息后异步执行：git fetch → collectDiff → review → format → postComment
 */
public class ReviewOrchestrator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private static final long TASK_TIMEOUT_SECONDS = 300; // 5 分钟超时

    private final ReviewConfig config;
    private final ExecutorService executor;
    private final ScheduledExecutorService timeoutExecutor;
    private final GitHubApiClient githubClient;

    public ReviewOrchestrator(ReviewConfig config) {
        this.config = config;
        this.executor = new ThreadPoolExecutor(
                1, 4, // 核心 1 线程，最大 4 线程
                60L, SECONDS, // 空闲线程 60s 回收
                new LinkedBlockingQueue<>(10), // 有界队列，最多积压 10 个任务
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时由提交线程执行，避免丢弃
        );
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        this.githubClient = new GitHubApiClient(config);
    }

    /**
     * 包内可见构造方法，用于测试注入 mock。
     */
    ReviewOrchestrator(ReviewConfig config, GitHubApiClient githubClient) {
        this.config = config;
        this.executor = new ThreadPoolExecutor(
                1, 4, 60L, SECONDS,
                new LinkedBlockingQueue<>(10),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        this.githubClient = githubClient;
    }

    /**
     * 异步处理 PR 审查任务。
     * 立即返回，审查在线程池中执行，带超时保护。
     */
    public void processAsync(GitHubPayloadParser.ParsedPullRequest pr) {
        Future<?> future = executor.submit(() -> {
            try {
                processInternal(pr);
            } catch (Exception e) {
                log.error("审查失败 {}/pull/{}: {}", pr.getRepoFullName(), pr.getPrNumber(), e.getMessage(), e);
                postErrorComment(pr, e);
            }
        });

        // 超时监控：防止 LLM 调用挂起导致线程永久占用
        timeoutExecutor.schedule(() -> {
            if (!future.isDone()) {
                future.cancel(true);
                log.warn("审查超时 {}/pull/{}，已取消（{}s）", pr.getRepoFullName(), pr.getPrNumber(), TASK_TIMEOUT_SECONDS);
                postErrorComment(pr, new RuntimeException("审查超时（" + TASK_TIMEOUT_SECONDS + "s）"));
            }
        }, TASK_TIMEOUT_SECONDS, SECONDS);
    }

    private void processInternal(GitHubPayloadParser.ParsedPullRequest pr) throws Exception {
        // 1. 查找本地仓库路径
        Path localPath = config.getWebhook().resolveLocalPath(pr.getRepoFullName());
        if (localPath == null) {
            log.warn("未配置仓库的本地路径：{}", pr.getRepoFullName());
            return;
        }

        // 2. git fetch 确保本地有最新 ref
        runGitFetch(localPath, pr.getHeadRef());

        // 3. 启用静默模式，抑制 ProgressDisplay 的控制台输出
        ProgressDisplay.setSilent(true);
        try {
            // 4. 收集 diff
            List<DiffFileEntry> diffEntries = DiffCollector.collectDiffBetweenRefs(
                    localPath, pr.getBaseRef(), pr.getHeadSha(), config);

            if (diffEntries.isEmpty()) {
                log.info("无差异条目：{}/pull/{}", pr.getRepoFullName(), pr.getPrNumber());
                return;
            }

            // AST enrichment (sidecar)
            diffEntries = new com.diffguard.ast.ASTEnricher(localPath, config).enrich(diffEntries);

            // 5. 执行审查
            ReviewEngineFactory.EngineType engineType =
                    ReviewEngineFactory.resolveEngineType(config, false, false);
            try (ReviewEngine engine = ReviewEngineFactory.create(engineType, config, localPath, diffEntries, false)) {
                ReviewResult result = engine.review(diffEntries, localPath);

                // 6. 格式化为 Markdown
                String markdown = MarkdownFormatter.format(result);

                // 7. 发布 PR 评论
                githubClient.postComment(pr.getRepoFullName(), pr.getPrNumber(), markdown);

                log.info("审查完成：{}/pull/{} - {} 个问题",
                        pr.getRepoFullName(), pr.getPrNumber(), result.getIssues().size());
            }
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
