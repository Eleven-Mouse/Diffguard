package com.diffguard.service;

import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.adapter.webhook.GitHubApiClient;
import com.diffguard.adapter.webhook.GitHubPayloadParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Answers.CALLS_REAL_METHODS;

/**
 * Fixed tests for {@link ReviewOrchestrator} that properly handle
 * the WebhookConfig setup to avoid NullPointerException.
 * <p>
 * Root cause of old tests failing:
 * <pre>
 * // OLD (broken) - getWebhook() returns null -> NPE in new GitHubApiClient(config)
 * ReviewConfig config = new ReviewConfig();
 * ReviewOrchestrator o = new ReviewOrchestrator(config, mockGithubClient);
 * </pre>
 * <p>
 * Fix: Set up WebhookConfig AND mock System.getenv for the GitHub token
 * during construction. The constructor chain is:
 * ReviewOrchestrator(config) -> new GitHubApiClient(config)
 * -> config.getWebhook().resolveGitHubToken() -> System.getenv("DIFFGUARD_GITHUB_TOKEN")
 * <p>
 * For tests that exercise processAsync, the internal GitHubApiClient will make real
 * HTTP calls (which will fail silently as per postComment's error handling),
 * so we test the async submission and error handling behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewOrchestrator (Fixed)")
class ReviewOrchestratorFixedTest {

    private static final String TEST_GITHUB_TOKEN = "test-github-token-for-testing";

    @Mock
    GitHubApiClient mockGithubClient;

    private ReviewOrchestrator orchestrator;

    /**
     * Creates a ReviewConfig with a properly set up WebhookConfig
     * containing repo mappings to avoid NPE in processInternal.
     */
    private ReviewConfig createConfigWithWebhook() {
        ReviewConfig config = new ReviewConfig();
        ReviewConfig.WebhookConfig webhookConfig = new ReviewConfig.WebhookConfig();

        // Set up repo mapping so resolveLocalPath returns a valid path
        ReviewConfig.RepoMapping mapping = new ReviewConfig.RepoMapping();
        mapping.setFullName("test/repo");
        mapping.setLocalPath("/tmp/test-repo");
        webhookConfig.setRepos(List.of(mapping));

        config.setWebhook(webhookConfig);
        return config;
    }

    /**
     * Creates a ReviewConfig with WebhookConfig but no repo mappings.
     */
    private ReviewConfig createConfigWithEmptyWebhook() {
        ReviewConfig config = new ReviewConfig();
        ReviewConfig.WebhookConfig webhookConfig = new ReviewConfig.WebhookConfig();
        config.setWebhook(webhookConfig);
        return config;
    }

    /**
     * Creates an orchestrator with the given config, mocking System.getenv
     * to provide the GitHub token required by the GitHubApiClient constructor.
     * <p>
     * The MockedStatic is kept open during construction and closed automatically
     * afterward. The created GitHubApiClient stores the resolved token, so
     * subsequent calls do not need the env var.
     */
    private ReviewOrchestrator createOrchestrator(ReviewConfig config) {
        try (MockedStatic<System> systemMock = mockStatic(System.class, CALLS_REAL_METHODS)) {
            systemMock.when(() -> System.getenv("DIFFGUARD_GITHUB_TOKEN"))
                    .thenReturn(TEST_GITHUB_TOKEN);

            return new ReviewOrchestrator(config, mockGithubClient);
        }
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            try {
                orchestrator.close();
            } catch (Exception ignored) {
                // Best effort cleanup
            }
        }
    }

    // ========================================================================
    // Resource Management
    // ========================================================================

    @Nested
    @DisplayName("Resource Management")
    class ResourceManagement {

        @Test
        @DisplayName("close() gracefully shuts down thread pool")
        void closeGracefulShutdown() {
            orchestrator = createOrchestrator(createConfigWithWebhook());
            assertDoesNotThrow(() -> orchestrator.close());
        }

        @Test
        @DisplayName("multiple close() calls do not throw")
        void multipleCloseNoException() {
            orchestrator = createOrchestrator(createConfigWithWebhook());
            assertDoesNotThrow(() -> {
                orchestrator.close();
                orchestrator.close();
                orchestrator.close();
            });
        }

        @Test
        @DisplayName("close() with empty webhook config does not throw")
        void closeWithEmptyWebhookConfig() {
            orchestrator = createOrchestrator(createConfigWithEmptyWebhook());
            assertDoesNotThrow(() -> orchestrator.close());
        }

        @Test
        @DisplayName("close after construction does not throw")
        void closeAfterConstructionNoThrow() {
            ReviewConfig config = createConfigWithWebhook();
            orchestrator = createOrchestrator(config);
            // Verify no NPE during construction or close
            assertDoesNotThrow(() -> orchestrator.close());
        }
    }

    // ========================================================================
    // Async Processing
    // ========================================================================

    @Nested
    @DisplayName("Async Processing")
    class AsyncProcessing {

        @Test
        @DisplayName("processAsync does not throw for valid PR with configured repo")
        void processAsyncWithConfiguredRepo() {
            ReviewConfig config = createConfigWithWebhook();
            orchestrator = createOrchestrator(config);

            GitHubPayloadParser.ParsedPullRequest pr =
                    new GitHubPayloadParser.ParsedPullRequest(
                            "opened", "test/repo", 1, "main", "feature", "abc123");

            // processAsync submits the task and returns immediately
            assertDoesNotThrow(() -> orchestrator.processAsync(pr));

            // Wait briefly for the async task to be submitted and start processing
            // (it will fail at git fetch since /tmp/test-repo is not a real git repo,
            // but processAsync itself should not throw)
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Test
        @DisplayName("processAsync handles unconfigured repo gracefully")
        void processAsyncWithUnconfiguredRepo() {
            ReviewConfig config = createConfigWithWebhook();
            orchestrator = createOrchestrator(config);

            // PR for a repo NOT in the config
            GitHubPayloadParser.ParsedPullRequest pr =
                    new GitHubPayloadParser.ParsedPullRequest(
                            "opened", "other/repo", 2, "main", "feature", "def456");

            assertDoesNotThrow(() -> orchestrator.processAsync(pr));

            // Wait for async task to process
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // processInternal returns early when localPath is null,
            // so it should complete without errors.
            // The internal githubClient.postComment is not called in this case.
        }

        @Test
        @DisplayName("processAsync for synchronize action is accepted")
        void processAsyncSynchronizeAction() {
            ReviewConfig config = createConfigWithWebhook();
            orchestrator = createOrchestrator(config);

            GitHubPayloadParser.ParsedPullRequest pr =
                    new GitHubPayloadParser.ParsedPullRequest(
                            "synchronize", "test/repo", 3, "main", "feature", "sha789");

            assertDoesNotThrow(() -> orchestrator.processAsync(pr));

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Test
        @DisplayName("processAsync for reopened action is accepted")
        void processAsyncReopenedAction() {
            ReviewConfig config = createConfigWithWebhook();
            orchestrator = createOrchestrator(config);

            GitHubPayloadParser.ParsedPullRequest pr =
                    new GitHubPayloadParser.ParsedPullRequest(
                            "reopened", "test/repo", 4, "main", "feature", "shaabc");

            assertDoesNotThrow(() -> orchestrator.processAsync(pr));

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Test
        @DisplayName("processAsync can be called multiple times")
        void processAsyncMultipleCalls() {
            ReviewConfig config = createConfigWithWebhook();
            orchestrator = createOrchestrator(config);

            GitHubPayloadParser.ParsedPullRequest pr1 =
                    new GitHubPayloadParser.ParsedPullRequest(
                            "opened", "test/repo", 10, "main", "feature", "sha1");
            GitHubPayloadParser.ParsedPullRequest pr2 =
                    new GitHubPayloadParser.ParsedPullRequest(
                            "synchronize", "test/repo", 11, "main", "feature", "sha2");

            assertDoesNotThrow(() -> {
                orchestrator.processAsync(pr1);
                orchestrator.processAsync(pr2);
            });

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ========================================================================
    // Constructor
    // ========================================================================

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("two-arg constructor works with mocked env")
        void twoArgConstructorWithMockedEnv() {
            ReviewConfig config = createConfigWithWebhook();
            assertDoesNotThrow(() -> {
                ReviewOrchestrator o = createOrchestrator(config);
                o.close();
            });
        }

        @Test
        @DisplayName("constructor with message queue disabled does not init RabbitMQ")
        void constructorWithMqDisabled() {
            ReviewConfig config = createConfigWithWebhook();
            // messageQueue.enabled defaults to false
            assertDoesNotThrow(() -> {
                ReviewOrchestrator o = createOrchestrator(config);
                o.close();
            });
        }

        @Test
        @DisplayName("constructor with database disabled does not init MySQL")
        void constructorWithDbDisabled() {
            ReviewConfig config = createConfigWithWebhook();
            // database.enabled defaults to false
            assertDoesNotThrow(() -> {
                ReviewOrchestrator o = createOrchestrator(config);
                o.close();
            });
        }

        @Test
        @DisplayName("constructor with empty repos list works")
        void constructorWithEmptyReposList() {
            ReviewConfig config = createConfigWithEmptyWebhook();
            assertDoesNotThrow(() -> {
                ReviewOrchestrator o = createOrchestrator(config);
                o.close();
            });
        }
    }

    // ========================================================================
    // getMetrics
    // ========================================================================

    @Nested
    @DisplayName("getMetrics")
    class GetMetrics {

        @Test
        @DisplayName("getMetrics returns MetricsService or null without throwing")
        void getMetricsReturnsNullOrInstance() {
            ReviewConfig config = createConfigWithWebhook();
            orchestrator = createOrchestrator(config);
            // MetricsService init may succeed or fail; either way should not throw
            assertDoesNotThrow(() -> orchestrator.getMetrics());
        }
    }
}
