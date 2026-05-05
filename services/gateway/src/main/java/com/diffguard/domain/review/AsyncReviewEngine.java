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

/**
 * 异步审查引擎：通过 taskId 轮询 MySQL 获取结果。
 * 用于 RabbitMQ 异步模式下，替代同步等待 Python HTTP 响应。
 */
public class AsyncReviewEngine implements ReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(AsyncReviewEngine.class);

    private final String taskId;
    private final ReviewConfig config;
    private final long pollIntervalMs = 2000;
    private final long timeoutMs;

    public AsyncReviewEngine(String taskId, ReviewConfig config) {
        this.taskId = taskId;
        this.config = config;
        this.timeoutMs = (config.getLlm().getTimeoutSeconds() + 60) * 1000L;
    }

    @Override
    public ReviewResult review(List<DiffFileEntry> diffEntries, Path projectDir) throws DiffGuardException {
        long start = System.currentTimeMillis();

        try {
            // Try to connect to MySQL and poll for result
            ReviewConfig.DatabaseConfigHolder dbConfig = config.getDatabase();
            if (dbConfig == null || !dbConfig.isEnabled()) {
                throw new DiffGuardException("Async mode requires database enabled");
            }

            com.diffguard.infrastructure.persistence.DatabaseConfig dbc = DatabaseConfig.fromEnv();
            DataSource ds = dbc.getDataSource();
            ReviewTaskRepository taskRepo = new ReviewTaskRepository(ds);
            ReviewResultRepository resultRepo = new ReviewResultRepository(ds);

            // Mark task as running
            taskRepo.updateStatus(taskId, "RUNNING");

            // Poll until completed or timeout
            while (System.currentTimeMillis() - start < timeoutMs) {
                Thread.sleep(pollIntervalMs);

                // Check if results exist in DB
                List<ReviewIssue> issues = resultRepo.findByTaskId(taskId);
                if (!issues.isEmpty()) {
                    ReviewResult result = new ReviewResult();
                    for (ReviewIssue issue : issues) {
                        result.addIssue(issue);
                    }
                    result.setHasCriticalFlag(issues.stream()
                            .anyMatch(i -> i.getSeverity() == Severity.CRITICAL));
                    result.setTotalFilesReviewed(diffEntries.size());
                    result.setReviewDurationMs(System.currentTimeMillis() - start);

                    taskRepo.updateStatus(taskId, "COMPLETED");
                    dbc.close();
                    return result;
                }
            }

            // Timeout
            taskRepo.updateError(taskId, "Review timed out after " + timeoutMs + "ms");
            dbc.close();
            throw new DiffGuardException("Async review timed out: " + taskId);

        } catch (DiffGuardException e) {
            throw e;
        } catch (Exception e) {
            throw new DiffGuardException("Async review failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // No resources to close
    }

    public String getTaskId() {
        return taskId;
    }
}
