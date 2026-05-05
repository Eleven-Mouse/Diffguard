package com.diffguard.infrastructure.persistence;

import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * review_result 表的 CRUD 操作。
 */
public class ReviewResultRepository {

    private static final Logger log = LoggerFactory.getLogger(ReviewResultRepository.class);

    private final DataSource dataSource;

    public ReviewResultRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 批量保存 Review 结果。
     */
    public void saveResult(String taskId, ReviewResult result) {
        String sql = "INSERT INTO review_result " +
                "(id, task_id, severity, file_path, line_number, issue_type, " +
                "message, suggestion, agent_type, confidence, source) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (ReviewIssue issue : result.getIssues()) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, taskId);
                    ps.setString(3, issue.getSeverity().name());
                    ps.setString(4, truncate(issue.getFile(), 500));
                    ps.setInt(5, issue.getLine());
                    ps.setString(6, truncate(issue.getType(), 100));
                    ps.setString(7, issue.getMessage());
                    ps.setString(8, issue.getSuggestion());
                    ps.setString(9, "LLM_AGENT");
                    ps.setFloat(10, 0.8f);
                    ps.setString(11, "LLM_AGENT");
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            log.debug("Saved {} issues for task {}", result.getIssues().size(), taskId);
        } catch (SQLException e) {
            log.error("Failed to save results for task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 批量保存静态规则检测结果。
     */
    public void saveStaticIssues(String taskId, List<ReviewIssue> issues) {
        String sql = "INSERT INTO review_result " +
                "(id, task_id, severity, file_path, line_number, issue_type, " +
                "message, suggestion, agent_type, confidence, source) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1.0, 'STATIC_RULE')";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (ReviewIssue issue : issues) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, taskId);
                    ps.setString(3, issue.getSeverity().name());
                    ps.setString(4, truncate(issue.getFile(), 500));
                    ps.setInt(5, issue.getLine());
                    ps.setString(6, truncate(issue.getType(), 100));
                    ps.setString(7, issue.getMessage());
                    ps.setString(8, issue.getSuggestion());
                    ps.setString(9, "STATIC_RULE");
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            log.debug("Saved {} static issues for task {}", issues.size(), taskId);
        } catch (SQLException e) {
            log.error("Failed to save static issues for task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 查询某任务的所有 issues。
     */
    public List<ReviewIssue> findByTaskId(String taskId) {
        String sql = "SELECT severity, file_path, line_number, issue_type, message, suggestion " +
                "FROM review_result WHERE task_id = ?";
        List<ReviewIssue> issues = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ReviewIssue issue = new ReviewIssue();
                    issue.setSeverity(com.diffguard.domain.review.model.Severity.fromString(rs.getString("severity")));
                    issue.setFile(rs.getString("file_path"));
                    issue.setLine(rs.getInt("line_number"));
                    issue.setType(rs.getString("issue_type"));
                    issue.setMessage(rs.getString("message"));
                    issue.setSuggestion(rs.getString("suggestion"));
                    issues.add(issue);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query results for task {}: {}", taskId, e.getMessage());
        }
        return issues;
    }

    private static String truncate(String s, int maxLen) {
        return (s != null && s.length() > maxLen) ? s.substring(0, maxLen) : s;
    }
}
