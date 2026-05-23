package com.diffguard.review;

import com.diffguard.review.ReviewEngine;
import com.diffguard.review.model.DiffFileEntry;
import com.diffguard.review.model.ReviewIssue;
import com.diffguard.review.model.ReviewResult;
import com.diffguard.review.model.Severity;
import com.diffguard.platform.config.ReviewConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.*;

@DisplayName("ReviewExecutionAdapter")
class ReviewExecutionAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("remote mode returns orchestrator result")
    void remoteModeReturnsOrchestratorResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v1/orchestrator/reviews", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 202, """
                        {"task_id":"task-1","status":"RUNNING","review_mode":"SIMPLE","created_at":1710000000000}
                        """);
                return;
            }
            writeJson(exchange, 405, "{\"error\":\"method not allowed\"}");
        });
        server.createContext("/api/v1/orchestrator/reviews/task-1/result", exchange ->
                writeJson(exchange, 200, """
                        {
                          "task_id":"task-1",
                          "status":"completed",
                          "has_critical_flag":false,
                          "issues":[{"severity":"WARNING","file":"src/A.java","line":10,"type":"risk","message":"m","suggestion":"s"}],
                          "total_tokens_used":123,
                          "review_duration_ms":456,
                          "summary":"ok",
                          "error":null
                        }
                        """));
        server.start();

        try {
            ReviewConfig config = remoteConfig("http://localhost:" + server.getAddress().getPort(), true);
            ReviewExecutionAdapter adapter = new ReviewExecutionAdapter(config);
            ReviewResult result = adapter.review(
                    tempDir,
                    List.of(new DiffFileEntry("src/A.java", "@@ -1 +1 @@", 12)),
                    ReviewEngineFactory.EngineType.SIMPLE,
                    false
            );

            assertNotNull(result);
            assertEquals(123, result.getTotalTokensUsed());
            assertEquals(456, result.getReviewDurationMs());
            assertFalse(result.hasCriticalIssues());
            assertEquals(1, result.getIssues().size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("remote failure falls back to legacy when enabled")
    void remoteFailureFallsBackToLegacyWhenEnabled() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/api/v1/orchestrator/reviews", exchange ->
                writeJson(exchange, 503, "{\"code\":\"DOWNSTREAM_UNAVAILABLE\"}"));
        server.start();

        try (MockedStatic<ReviewEngineFactory> factoryMock =
                     mockStatic(ReviewEngineFactory.class, CALLS_REAL_METHODS)) {
            ReviewEngine mockEngine = mock(ReviewEngine.class);
            ReviewResult expected = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.WARNING);
            issue.setMessage("legacy");
            expected.addIssue(issue);

            when(mockEngine.review(anyList(), any(Path.class))).thenReturn(expected);
            factoryMock.when(() -> ReviewEngineFactory.create(
                            eq(ReviewEngineFactory.EngineType.SIMPLE), any(), any(), any(), eq(false)))
                    .thenReturn(mockEngine);

            ReviewConfig config = remoteConfig("http://localhost:" + server.getAddress().getPort(), true);
            ReviewExecutionAdapter adapter = new ReviewExecutionAdapter(config);
            ReviewResult result = adapter.review(
                    tempDir,
                    List.of(new DiffFileEntry("src/A.java", "@@", 1)),
                    ReviewEngineFactory.EngineType.SIMPLE,
                    false
            );

            assertNotNull(result);
            assertEquals(1, result.getIssues().size());
            verify(mockEngine).close();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("remote failure returns null when fallback disabled")
    void remoteFailureReturnsNullWhenFallbackDisabled() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/api/v1/orchestrator/reviews", exchange ->
                writeJson(exchange, 500, "{\"code\":\"INTERNAL_ERROR\"}"));
        server.start();

        try (MockedStatic<ReviewEngineFactory> factoryMock =
                     mockStatic(ReviewEngineFactory.class, CALLS_REAL_METHODS)) {
            ReviewConfig config = remoteConfig("http://localhost:" + server.getAddress().getPort(), false);
            ReviewExecutionAdapter adapter = new ReviewExecutionAdapter(config);
            List<DiffFileEntry> entries = List.of(new DiffFileEntry("src/A.java", "@@", 1));
            ReviewResult result = adapter.review(
                    tempDir,
                    entries,
                    ReviewEngineFactory.EngineType.SIMPLE,
                    false
            );

            assertNull(result);
            factoryMock.verify(() -> ReviewEngineFactory.create(
                    ReviewEngineFactory.EngineType.SIMPLE, config, tempDir, entries, false), never());
        } finally {
            server.stop(0);
        }
    }

    private static ReviewConfig remoteConfig(String url, boolean fallbackToLegacy) {
        ReviewConfig config = new ReviewConfig();
        ReviewConfig.OrchestratorConfig orchestrator = new ReviewConfig.OrchestratorConfig();
        orchestrator.setMode("remote");
        orchestrator.setUrl(url);
        orchestrator.setFallbackToLegacy(fallbackToLegacy);
        orchestrator.setTimeoutSeconds(5);
        orchestrator.setPollIntervalMs(50);
        config.setOrchestrator(orchestrator);
        return config;
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        } finally {
            exchange.close();
        }
    }
}
