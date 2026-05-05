package com.diffguard.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;

/**
 * review_task 表的 CRUD 操作。
 */
public class ReviewTaskRepository {

    private static final Logger log = LoggerFactory.getLogger(ReviewTaskRepository.class);

    private final DataSource dataSource;

    public ReviewTaskRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 插入一条新任务。
     */
    public void insert(String taskId, String repoName, int prNumber,
                       String mode, int diffFileCount) {
        String sql = "INSERT INTO review_task (id, repo_name, pr_number, mode, status, diff_file_count) " +
                     "VALUES (?, ?, ?, ?, 'PENDING', ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setString(2, repoName);
            ps.setInt(3, prNumber);
            ps.setString(4, mode);
            ps.setInt(5, diffFileCount);
            ps.executeUpdate();
            log.debug("Inserted review task: {}", taskId);
        } catch (SQLException e) {
            log.error("Failed to insert task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 更新任务状态。
     */
    public void updateStatus(String taskId, String status) {
        String sql = "UPDATE review_task SET status = ?";
        if ("RUNNING".equals(status)) {
            sql += ", started_at = CURRENT_TIMESTAMP(3)";
        } else if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            sql += ", completed_at = CURRENT_TIMESTAMP(3)";
        }
        sql += " WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, taskId);
            int rows = ps.executeUpdate();
            log.debug("Updated task {} to status {} (rows={})", taskId, status, rows);
        } catch (SQLException e) {
            log.error("Failed to update task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 更新错误信息。
     */
    public void updateError(String taskId, String errorMessage) {
        String sql = "UPDATE review_task SET status = 'FAILED', error_message = ?, " +
                     "completed_at = CURRENT_TIMESTAMP(3) WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, truncate(errorMessage, 4000));
            ps.setString(2, taskId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update error for task {}: {}", taskId, e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        return (s != null && s.length() > maxLen) ? s.substring(0, maxLen) : s;
    }
}
