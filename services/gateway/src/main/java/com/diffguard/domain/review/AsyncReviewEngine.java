package com.diffguard.domain.review;

import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.domain.review.model.DiffFileEntry;
import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.domain.review.model.Severity;
import com.diffguard.infrastructure.persistence.DatabaseConfig;
import com.diffguard.infrastructure.persistence.ReviewResultRepository;
import com.diffguard.infrastructure.persistence.ReviewTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

/**
 * 异步审查引擎：通过 taskId 轮询 MySQL 获取结果。
 * 用于 RabbitMQ 异步模式下，替代同步等待 Python HTTP 响应。
 */
public class AsyncReviewEngine implements ReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(AsyncReviewEngine.class);

    private static final ScheduledExecutorService POLL_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "async-review-poll");
                t.setDaemon(true);
                return t;
            });

    /** Shared DatabaseConfig singleton — created once, reused across reviews. */
    private static volatile DatabaseConfig sharedDbConfig;
    private static final Object DB_LOCK = new Object();

    private final String taskId;
    private final ReviewConfig config;
    private final long pollIntervalMs = 2000;
    private final long timeoutMs;

    public AsyncReviewEngine(String taskId, ReviewConfig config) {
        this.taskId = taskId;
        this.config = config;
        this.timeoutMs = (config.getLlm().getTimeoutSeconds() + 60) * 1000L;
    }

    private static DatabaseConfig getSharedDbConfig() {
        if (sharedDbConfig == null) {
            synchronized (DB_LOCK) {
                if (sharedDbConfig == null) {
                    sharedDbConfig = DatabaseConfig.fromEnv();
                }
            }
        }
        return sharedDbConfig;
    }

    @Override
    public ReviewResult review(List<DiffFileEntry> diffEntries, Path projectDir) throws DiffGuardException {
        ReviewConfig.DatabaseConfigHolder dbConfig = config.getDatabase();
        if (dbConfig == null || !dbConfig.isEnabled()) {
            throw new DiffGuardException("Async mode requires database enabled");
        }

        DatabaseConfig dbc = getSharedDbConfig();
        DataSource ds = dbc.getDataSource();
        ReviewTaskRepository taskRepo = new ReviewTaskRepository(ds);
        ReviewResultRepository resultRepo = new ReviewResultRepository(ds);

        taskRepo.updateStatus(taskId, "RUNNING");

        CompletableFuture<ReviewResult> future = new CompletableFuture<>();

        // Schedule polling off the calling thread
        ScheduledFuture<?> pollHandle = POLL_EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                List<ReviewIssue> issues = resultRepo.findByTaskId(taskId);
                if (!issues.isEmpty()) {
                    ReviewResult result = new ReviewResult();
                    for (ReviewIssue issue : issues) {
                        result.addIssue(issue);
                    }
                    result.setHasCriticalFlag(issues.stream()
                            .anyMatch(i -> i.getSeverity() == Severity.CRITICAL));
                    result.setTotalFilesReviewed(diffEntries.size());
                    future.complete(result);
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);

        // Timeout guard
        POLL_EXECUTOR.schedule(() -> {
            if (!future.isDone()) {
                taskRepo.updateError(taskId, "Review timed out after " + timeoutMs + "ms");
                future.completeExceptionally(new DiffGuardException("Async review timed out: " + taskId));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        try {
            ReviewResult result = future.get();
            pollHandle.cancel(false);
            taskRepo.updateStatus(taskId, "COMPLETED");
            result.setReviewDurationMs(System.currentTimeMillis());
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pollHandle.cancel(false);
            throw new DiffGuardException("Async review interrupted: " + taskId, e);
        } catch (ExecutionException e) {
            pollHandle.cancel(false);
            Throwable cause = e.getCause();
            if (cause instanceof DiffGuardException dge) throw dge;
            throw new DiffGuardException("Async review failed: " + cause.getMessage(), cause);
        }
    }

    @Override
    public void close() {
        // Shared resources are not closed per-instance
    }
}
