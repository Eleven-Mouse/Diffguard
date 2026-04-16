package com.diffguard.webhook;

import com.diffguard.config.ReviewConfig;
import com.diffguard.diff.DiffCollector;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.ReviewResult;
import com.diffguard.output.MarkdownFormatter;
import com.diffguard.service.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Webhook 触发的代码审查编排器。
 * 接收 PR 信息后异步执行：git fetch → collectDiff → review → format → postComment
 */
public class ReviewOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private final ReviewConfig config;
    private final ExecutorService executor;
    private final GitHubApiClient githubClient;

    public ReviewOrchestrator(ReviewConfig config) {
        this.config = config;
        this.executor = Executors.newSingleThreadExecutor();
        this.githubClient = new GitHubApiClient(config);
    }

    /**
     * 异步处理 PR 审查任务。
     * 立即返回，审查在线程池中执行。
     */
    public void processAsync(GitHubPayloadParser.ParsedPullRequest pr) {
        executor.submit(() -> {
            try {
                processInternal(pr);
            } catch (Exception e) {
                log.error("审查失败 {}/pull/{}: {}", pr.getRepoFullName(), pr.getPrNumber(), e.getMessage(), e);
                postErrorComment(pr, e);
            }
        });
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

        // 3. 抑制 ProgressDisplay 输出
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            PrintStream silentOut = new PrintStream(new ByteArrayOutputStream());
            System.setOut(silentOut);
            System.setErr(silentOut);

            // 4. 收集 diff
            List<DiffFileEntry> diffEntries = DiffCollector.collectDiffBetweenRefs(
                    localPath, pr.getBaseRef(), pr.getHeadSha(), config);

            if (diffEntries.isEmpty()) {
                log.info("无差异条目：{}/pull/{}", pr.getRepoFullName(), pr.getPrNumber());
                return;
            }

            // 5. 执行审查
            ReviewService reviewService = new ReviewService(config, localPath, false);
            ReviewResult result = reviewService.review(diffEntries);

            // 6. 格式化为 Markdown
            String markdown = MarkdownFormatter.format(result);

            // 7. 发布 PR 评论
            githubClient.postComment(pr.getRepoFullName(), pr.getPrNumber(), markdown);

            log.info("审查完成：{}/pull/{} - {} 个问题",
                    pr.getRepoFullName(), pr.getPrNumber(), result.getIssues().size());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private void runGitFetch(Path projectDir, String ref) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "fetch", "origin", ref);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
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
}
