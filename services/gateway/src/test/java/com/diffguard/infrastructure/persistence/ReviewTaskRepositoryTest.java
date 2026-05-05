package com.diffguard.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewTaskRepository")
class ReviewTaskRepositoryTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    private ReviewTaskRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        repository = new ReviewTaskRepository(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
    }

    // ------------------------------------------------------------------
    // insert
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("insert")
    class Insert {

        @Test
        @DisplayName("insert executes INSERT with correct parameters")
        void insertExecutesSql() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            repository.insert("task-123", "my-repo", 42, "FULL", 5);

            verify(connection).prepareStatement(contains("INSERT INTO review_task"));
            verify(preparedStatement).setString(1, "task-123");
            verify(preparedStatement).setString(2, "my-repo");
            verify(preparedStatement).setInt(3, 42);
            verify(preparedStatement).setString(4, "FULL");
            verify(preparedStatement).setInt(5, 5);
            verify(preparedStatement).executeUpdate();
        }

        @Test
        @DisplayName("insert handles SQLException gracefully")
        void insertHandlesSqlException() throws SQLException {
            when(connection.prepareStatement(anyString())).thenThrow(new SQLException("DB error"));

            // Should not throw - error is logged
            assertDoesNotThrow(() -> repository.insert("task-456", "repo", 1, "FULL", 1));
        }

        @Test
        @DisplayName("insert closes connection and statement")
        void insertClosesResources() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            repository.insert("task-789", "repo", 1, "FULL", 1);

            verify(preparedStatement).close();
            verify(connection).close();
        }
    }

    // ------------------------------------------------------------------
    // updateStatus
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatus {

        @Test
        @DisplayName("updateStatus to RUNNING adds started_at timestamp")
        void updateStatusRunningAddsTimestamp() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            repository.updateStatus("task-1", "RUNNING");

            verify(connection).prepareStatement(argThat(sql ->
                    sql.contains("started_at") && sql.contains("CURRENT_TIMESTAMP")));
            verify(preparedStatement).setString(1, "RUNNING");
            verify(preparedStatement).setString(2, "task-1");
        }

        @Test
        @DisplayName("updateStatus to COMPLETED adds completed_at timestamp")
        void updateStatusCompletedAddsTimestamp() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            repository.updateStatus("task-2", "COMPLETED");

            verify(connection).prepareStatement(argThat(sql ->
                    sql.contains("completed_at") && sql.contains("CURRENT_TIMESTAMP")));
        }

        @Test
        @DisplayName("updateStatus to FAILED adds completed_at timestamp")
        void updateStatusFailedAddsTimestamp() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            repository.updateStatus("task-3", "FAILED");

            verify(connection).prepareStatement(argThat(sql ->
                    sql.contains("completed_at") && sql.contains("CURRENT_TIMESTAMP")));
        }

        @Test
        @DisplayName("updateStatus to PENDING does not add timestamp")
        void updateStatusPendingNoTimestamp() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            repository.updateStatus("task-4", "PENDING");

            verify(connection).prepareStatement(argThat(sql ->
                    !sql.contains("started_at") && !sql.contains("completed_at")));
        }

        @Test
        @DisplayName("updateStatus handles SQLException gracefully")
        void updateStatusHandlesException() throws SQLException {
            when(connection.prepareStatement(anyString())).thenThrow(new SQLException("error"));

            assertDoesNotThrow(() -> repository.updateStatus("task-5", "RUNNING"));
        }

        @Test
        @DisplayName("updateStatus sets correct parameters")
        void updateStatusSetsParameters() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            repository.updateStatus("task-6", "COMPLETED");

            verify(preparedStatement).setString(1, "COMPLETED");
            verify(preparedStatement).setString(2, "task-6");
            verify(preparedStatement).executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // updateError
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("updateError")
    class UpdateError {

        @Test
        @DisplayName("updateError sets FAILED status and error message")
        void updateErrorSetsFailedStatus() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            repository.updateError("task-10", "Something went wrong");

            verify(connection).prepareStatement(contains("FAILED"));
            verify(preparedStatement).setString(1, "Something went wrong");
            verify(preparedStatement).setString(2, "task-10");
            verify(preparedStatement).executeUpdate();
        }

        @Test
        @DisplayName("updateError truncates long error messages")
        void updateErrorTruncatesLongMessage() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            String longMessage = "x".repeat(5000);
            repository.updateError("task-11", longMessage);

            verify(preparedStatement).setString(1, argThat(msg ->
                    msg != null && msg.length() <= 4000));
        }

        @Test
        @DisplayName("updateError handles SQLException gracefully")
        void updateErrorHandlesException() throws SQLException {
            when(connection.prepareStatement(anyString())).thenThrow(new SQLException("error"));

            assertDoesNotThrow(() -> repository.updateError("task-12", "error msg"));
        }

        @Test
        @DisplayName("updateError handles null message")
        void updateErrorNullMessage() throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

            assertDoesNotThrow(() -> repository.updateError("task-13", null));
            verify(preparedStatement).setString(1, null);
        }
    }
}
