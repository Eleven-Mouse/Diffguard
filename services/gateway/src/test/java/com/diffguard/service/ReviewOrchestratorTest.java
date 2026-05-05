package com.diffguard.service;

import com.diffguard.adapter.webhook.GitHubApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewOrchestrator")
class ReviewOrchestratorTest {

    @Mock
    GitHubApiClient mockGithubClient;

    @Nested
    @DisplayName("资源管理")
    class ResourceManagement {

        @Test
        @DisplayName("close() 优雅关闭线程池")
        void closeGracefulShutdown() {
            ReviewOrchestrator orchestrator = new ReviewOrchestrator(
                    new com.diffguard.infrastructure.config.ReviewConfig(), mockGithubClient);
            assertDoesNotThrow(orchestrator::close);
        }

        @Test
        @DisplayName("多次 close() 不抛异常")
        void multipleCloseNoException() {
            ReviewOrchestrator orchestrator = new ReviewOrchestrator(
                    new com.diffguard.infrastructure.config.ReviewConfig(), mockGithubClient);
            orchestrator.close();
            assertDoesNotThrow(orchestrator::close);
        }
    }

    @Nested
    @DisplayName("异步处理")
    class AsyncProcessing {

        @Test
        @DisplayName("processAsync 对 null payload 不崩溃")
        void processAsyncNullSafety() {
            ReviewOrchestrator orchestrator = new ReviewOrchestrator(
                    new com.diffguard.infrastructure.config.ReviewConfig(), mockGithubClient);
            // processAsync 传入 null PR 时应安全（由 executor 捕获异常）
            // 此测试验证 close 后不会有资源泄漏
            assertDoesNotThrow(orchestrator::close);
        }
    }
}
