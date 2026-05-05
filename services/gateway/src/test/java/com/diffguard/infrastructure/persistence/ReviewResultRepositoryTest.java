package com.diffguard.infrastructure.persistence;

import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.domain.review.model.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewResultRepository")
class ReviewResultRepositoryTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    private ReviewResultRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        repository = new ReviewResultRepository(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
    }

    // ------------------------------------------------------------------
    // saveResult
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("saveResult")
    class SaveResult {

        @Test
        @DisplayName("saveResult inserts issues from ReviewResult")
        void saveResultInsertsIssues() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            ReviewResult result = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.CRITICAL);
            issue.setFile("Test.java");
            issue.setLine(10);
            issue.setType("SECURITY");
            issue.setMessage("SQL injection");
            issue.setSuggestion("Use parameterized query");
            result.addIssue(issue);

            repository.saveResult("task-1", result);

            verify(connection).setAutoCommit(false);
            verify(preparedStatement, times(1)).addBatch();
            verify(preparedStatement).executeBatch();
            verify(connection).commit();
        }

        @Test
        @DisplayName("saveResult with multiple issues batches all")
        void saveResultMultipleIssues() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            ReviewResult result = new ReviewResult();
            for (int i = 0; i < 5; i++) {
                ReviewIssue issue = new ReviewIssue();
                issue.setSeverity(Severity.WARNING);
                issue.setFile("File" + i + ".java");
                issue.setLine(i + 1);
                issue.setType("STYLE");
                issue.setMessage("Issue " + i);
                issue.setSuggestion("Fix " + i);
                result.addIssue(issue);
            }

            repository.saveResult("task-2", result);

            verify(preparedStatement, times(5)).addBatch();
        }

        @Test
        @DisplayName("saveResult with empty issues list does not fail")
        void saveResultEmptyIssues() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            ReviewResult result = new ReviewResult();
            repository.saveResult("task-3", result);

            verify(connection).setAutoCommit(false);
            verify(preparedStatement).executeBatch();
        }

        @Test
        @DisplayName("saveResult handles SQLException gracefully")
        void saveResultHandlesException() throws SQLException {
            when(connection.prepareStatement(anyString())).thenThrow(new SQLException("DB error"));

            ReviewResult result = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.INFO);
            issue.setFile("test.java");
            issue.setLine(1);
            issue.setType("INFO");
            issue.setMessage("msg");
            issue.setSuggestion("sug");
            result.addIssue(issue);

            assertDoesNotThrow(() -> repository.saveResult("task-4", result));
        }

        @Test
        @DisplayName("saveResult truncates long file paths")
        void saveResultTruncatesLongFilePath() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            ReviewResult result = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.INFO);
            issue.setFile("a".repeat(600));
            issue.setLine(1);
            issue.setType("TYPE");
            issue.setMessage("msg");
            issue.setSuggestion("sug");
            result.addIssue(issue);

            repository.saveResult("task-5", result);

            verify(preparedStatement).setString(eq(4), argThat(s -> s != null && s.length() <= 500));
        }
    }

    // ------------------------------------------------------------------
    // saveStaticIssues
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("saveStaticIssues")
    class SaveStaticIssues {

        @Test
        @DisplayName("saveStaticIssues inserts issues with STATIC_RULE source")
        void saveStaticIssuesInsertsCorrectly() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.WARNING);
            issue.setFile("Config.java");
            issue.setLine(20);
            issue.setType("CODE_STYLE");
            issue.setMessage("Missing override annotation");
            issue.setSuggestion("Add @Override");

            repository.saveStaticIssues("task-10", List.of(issue));

            verify(connection).setAutoCommit(false);
            verify(preparedStatement).setString(9, "STATIC_RULE");
            verify(preparedStatement).addBatch();
            verify(preparedStatement).executeBatch();
            verify(connection).commit();
        }

        @Test
        @DisplayName("saveStaticIssues with empty list")
        void saveStaticIssuesEmptyList() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            repository.saveStaticIssues("task-11", List.of());

            verify(preparedStatement).executeBatch();
        }

        @Test
        @DisplayName("saveStaticIssues handles SQLException")
        void saveStaticIssuesHandlesException() throws SQLException {
            when(connection.prepareStatement(anyString())).thenThrow(new SQLException("error"));

            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.INFO);
            issue.setFile("f.java");
            issue.setLine(1);
            issue.setType("T");
            issue.setMessage("m");
            issue.setSuggestion("s");

            assertDoesNotThrow(() -> repository.saveStaticIssues("task-12", List.of(issue)));
        }
    }

    // ------------------------------------------------------------------
    // findByTaskId
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("findByTaskId")
    class FindByTaskId {

        @Test
        @DisplayName("findByTaskId returns issues from ResultSet")
        void findByTaskIdReturnsIssues() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
            when(preparedStatement.executeQuery()).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(true, true, false);
            when(resultSet.getString("severity")).thenReturn("CRITICAL", "WARNING");
            when(resultSet.getString("file_path")).thenReturn("A.java", "B.java");
            when(resultSet.getInt("line_number")).thenReturn(10, 20);
            when(resultSet.getString("issue_type")).thenReturn("BUG", "STYLE");
            when(resultSet.getString("message")).thenReturn("msg1", "msg2");
            when(resultSet.getString("suggestion")).thenReturn("fix1", "fix2");

            List<ReviewIssue> issues = repository.findByTaskId("task-20");

            assertEquals(2, issues.size());
            assertEquals(Severity.CRITICAL, issues.get(0).getSeverity());
            assertEquals("A.java", issues.get(0).getFile());
            assertEquals(10, issues.get(0).getLine());
            assertEquals(Severity.WARNING, issues.get(1).getSeverity());
            assertEquals("B.java", issues.get(1).getFile());
        }

        @Test
        @DisplayName("findByTaskId returns empty list when no results")
        void findByTaskIdEmptyResult() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
            when(preparedStatement.executeQuery()).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(false);

            List<ReviewIssue> issues = repository.findByTaskId("task-21");

            assertTrue(issues.isEmpty());
        }

        @Test
        @DisplayName("findByTaskId handles SQLException")
        void findByTaskIdHandlesException() throws SQLException {
            when(connection.prepareStatement(anyString())).thenThrow(new SQLException("error"));

            List<ReviewIssue> issues = repository.findByTaskId("task-22");

            assertTrue(issues.isEmpty());
        }

        @Test
        @DisplayName("findByTaskId sets taskId parameter")
        void findByTaskIdSetsParameter() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
            when(preparedStatement.executeQuery()).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(false);

            repository.findByTaskId("task-23");

            verify(preparedStatement).setString(1, "task-23");
        }
    }
}
