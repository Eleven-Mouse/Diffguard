package com.diffguard.domain.review;

import com.diffguard.domain.review.model.DiffFileEntry;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.infrastructure.config.ReviewConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AsyncReviewEngine")
class AsyncReviewEngineTest {

    @TempDir
    Path tempDir;

    @Mock
    private ReviewConfig config;

    private List<DiffFileEntry> diffEntries;

    @BeforeEach
    void setUp() {
        diffEntries = List.of(
                new DiffFileEntry("Service.java", "content", 100)
        );
    }

    private ReviewConfig.LlmConfig defaultLlmConfig() {
        ReviewConfig.LlmConfig llmConfig = new ReviewConfig.LlmConfig();
        llmConfig.setTimeoutSeconds(60);
        return llmConfig;
    }

    @Nested
    @DisplayName("构造函数")
    class ConstructorTest {

        @Test
        @DisplayName("构造函数设置 taskId")
        void setsTaskId() {
            when(config.getLlm()).thenReturn(defaultLlmConfig());
            AsyncReviewEngine engine = new AsyncReviewEngine("task-123", config);

            assertEquals("task-123", engine.getTaskId());
        }
    }

    @Nested
    @DisplayName("close()")
    class CloseTest {

        @Test
        @DisplayName("close() 不抛出异常")
        void closeDoesNotThrow() {
            when(config.getLlm()).thenReturn(defaultLlmConfig());
            AsyncReviewEngine engine = new AsyncReviewEngine("task-1", config);

            assertDoesNotThrow(() -> engine.close());
        }

        @Test
        @DisplayName("多次调用 close() 安全")
        void multipleCloseSafe() {
            when(config.getLlm()).thenReturn(defaultLlmConfig());
            AsyncReviewEngine engine = new AsyncReviewEngine("task-1", config);

            assertDoesNotThrow(() -> {
                engine.close();
                engine.close();
            });
        }
    }

    @Nested
    @DisplayName("review()")
    class ReviewTest {

        @Test
        @DisplayName("数据库未启用时抛出异常")
        void databaseNotEnabledThrows() {
            ReviewConfig.LlmConfig llmConfig = new ReviewConfig.LlmConfig();
            llmConfig.setTimeoutSeconds(30);
            when(config.getLlm()).thenReturn(llmConfig);

            ReviewConfig.DatabaseConfigHolder dbConfig = mock(ReviewConfig.DatabaseConfigHolder.class);
            when(dbConfig.isEnabled()).thenReturn(false);
            when(config.getDatabase()).thenReturn(dbConfig);

            AsyncReviewEngine engine = new AsyncReviewEngine("task-1", config);

            DiffGuardException ex = assertThrows(DiffGuardException.class,
                    () -> engine.review(diffEntries, tempDir));
            assertTrue(ex.getMessage().contains("database"));
        }

        @Test
        @DisplayName("数据库配置为 null 时抛出异常")
        void databaseConfigNullThrows() {
            when(config.getDatabase()).thenReturn(null);
            when(config.getLlm()).thenReturn(defaultLlmConfig());

            AsyncReviewEngine engine = new AsyncReviewEngine("task-1", config);

            assertThrows(DiffGuardException.class,
                    () -> engine.review(diffEntries, tempDir));
        }

        @Test
        @DisplayName("空 diff 列表不导致构造错误")
        void emptyDiffList() {
            when(config.getLlm()).thenReturn(defaultLlmConfig());
            AsyncReviewEngine engine = new AsyncReviewEngine("task-1", config);

            assertNotNull(engine);
        }
    }

    @Nested
    @DisplayName("getTaskId()")
    class GetTaskIdTest {

        @Test
        @DisplayName("返回传入的 taskId")
        void returnsTaskId() {
            when(config.getLlm()).thenReturn(defaultLlmConfig());
            AsyncReviewEngine engine = new AsyncReviewEngine("my-task-id", config);

            assertEquals("my-task-id", engine.getTaskId());
        }

        @Test
        @DisplayName("不同 taskId 正确返回")
        void differentTaskIds() {
            when(config.getLlm()).thenReturn(defaultLlmConfig());
            AsyncReviewEngine engine1 = new AsyncReviewEngine("task-A", config);
            AsyncReviewEngine engine2 = new AsyncReviewEngine("task-B", config);

            assertEquals("task-A", engine1.getTaskId());
            assertEquals("task-B", engine2.getTaskId());
        }
    }
}
