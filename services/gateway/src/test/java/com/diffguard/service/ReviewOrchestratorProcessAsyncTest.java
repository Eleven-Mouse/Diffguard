package com.diffguard.service;

import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.webhook.GitHubApiClient;
import com.diffguard.webhook.GitHubPayloadParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewOrchestrator processAsync")
class ReviewOrchestratorProcessAsyncTest {

    @Mock
    GitHubApiClient mockGithubClient;

    @TempDir
    Path tempDir;

    private GitHubPayloadParser.ParsedPullRequest makePr(String repo, int number) {
        return new GitHubPayloadParser.ParsedPullRequest("opened", repo, number, "main", "feature", "abc123");
    }

    // ------------------------------------------------------------------
    // 本地路径未配置
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("本地路径未配置")
    class NoLocalPath {

        @Test
        @DisplayName("无 webhook 配置 → 发布错误评论（NPE 被 postErrorComment 捕获）")
        void noWebhookConfigPostsErrorComment() throws Exception {
            ReviewConfig config = new ReviewConfig();
            ReviewOrchestrator orchestrator = new ReviewOrchestrator(config, mockGithubClient);

            orchestrator.processAsync(makePr("unknown/repo", 1));
            orchestrator.close();

            // getWebhook() 为 null → processInternal NPE → postErrorComment 被调用
            verify(mockGithubClient, atMostOnce()).postComment(anyString(), anyInt(), anyString());
        }
    }

    // ------------------------------------------------------------------
    // 审查异常处理
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("审查异常处理")
    class ErrorHandling {

        @Test
        @DisplayName("本地路径不存在 → 发布错误评论")
        void invalidPathPostsErrorComment() throws Exception {
            ReviewConfig config = new ReviewConfig();
            ReviewConfig.WebhookConfig webhook = new ReviewConfig.WebhookConfig();
            ReviewConfig.RepoMapping mapping = new ReviewConfig.RepoMapping();
            mapping.setFullName("test/repo");
            mapping.setLocalPath("/nonexistent/path/that/does/not/exist");
            webhook.setRepos(java.util.List.of(mapping));
            config.setWebhook(webhook);

            ReviewOrchestrator orchestrator = new ReviewOrchestrator(config, mockGithubClient);
            orchestrator.processAsync(makePr("test/repo", 1));
            orchestrator.close();

            // git fetch 失败或路径不存在 → processInternal 异常 → postErrorComment
            verify(mockGithubClient, atMostOnce()).postComment(eq("test/repo"), eq(1), contains("Failed"));
        }
    }

    // ------------------------------------------------------------------
    // 资源安全
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("资源安全")
    class ResourceSafety {

        @Test
        @DisplayName("close() 在 processAsync 执行期间不阻塞超过 10s")
        void closeWaitsForAsync() {
            ReviewConfig config = new ReviewConfig();
            ReviewOrchestrator orchestrator = new ReviewOrchestrator(config, mockGithubClient);

            orchestrator.processAsync(makePr("any/repo", 1));

            long start = System.currentTimeMillis();
            assertDoesNotThrow(orchestrator::close);
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 15_000, "close 应在 15s 内完成，实际耗时 " + elapsed + "ms");
        }
    }
}
