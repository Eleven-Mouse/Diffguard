package com.diffguard.orchestrator;

import com.diffguard.platform.common.JacksonMapper;
import com.diffguard.platform.config.ReviewConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReviewOrchestratorServer Contract")
class ReviewOrchestratorServerContractTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("returns 400 INVALID_REQUEST when mode missing")
    void returns400WhenModeMissing() throws Exception {
        withServer((baseUrl, client) -> {
            ObjectNode req = baseRequest();
            req.remove("mode");
            HttpResponse<String> resp = post(baseUrl, client, req, null);
            assertEquals(400, resp.statusCode());
            JsonNode body = JacksonMapper.MAPPER.readTree(resp.body());
            assertEquals("INVALID_REQUEST", body.path("code").asText());
        });
    }

    @Test
    @DisplayName("returns 422 MISSING_TOOL_SERVER_URL when tool_server_url missing")
    void returns422WhenToolServerMissing() throws Exception {
        withServer((baseUrl, client) -> {
            ObjectNode req = baseRequest();
            req.remove("tool_server_url");
            HttpResponse<String> resp = post(baseUrl, client, req, null);
            assertEquals(422, resp.statusCode());
            JsonNode body = JacksonMapper.MAPPER.readTree(resp.body());
            assertEquals("MISSING_TOOL_SERVER_URL", body.path("code").asText());
        });
    }

    @Test
    @DisplayName("returns 400 INVALID_REQUEST when mode unsupported")
    void returns400WhenModeUnsupported() throws Exception {
        withServer((baseUrl, client) -> {
            ObjectNode req = baseRequest();
            req.put("mode", "UNKNOWN");
            HttpResponse<String> resp = post(baseUrl, client, req, null);
            assertEquals(400, resp.statusCode());
            JsonNode body = JacksonMapper.MAPPER.readTree(resp.body());
            assertEquals("INVALID_REQUEST", body.path("code").asText());
            assertTrue(body.path("message").asText().contains("unsupported mode"));
        });
    }

    @Test
    @DisplayName("returns 400 INVALID_REQUEST when diff_entries empty")
    void returns400WhenDiffEntriesEmpty() throws Exception {
        withServer((baseUrl, client) -> {
            ObjectNode req = baseRequest();
            req.putArray("diff_entries");
            HttpResponse<String> resp = post(baseUrl, client, req, null);
            assertEquals(400, resp.statusCode());
            JsonNode body = JacksonMapper.MAPPER.readTree(resp.body());
            assertEquals("INVALID_REQUEST", body.path("code").asText());
            assertTrue(body.path("message").asText().contains("diff_entries"));
        });
    }

    @Test
    @DisplayName("reuses task_id for same X-Idempotency-Key and payload")
    void reusesTaskIdForSameIdempotencyKey() throws Exception {
        withServer((baseUrl, client) -> {
            ObjectNode req = baseRequest();
            String key = "repo:1:sha:simple";

            HttpResponse<String> resp1 = post(baseUrl, client, req, key);
            assertEquals(202, resp1.statusCode());
            String task1 = JacksonMapper.MAPPER.readTree(resp1.body()).path("task_id").asText();
            assertFalse(task1.isBlank());

            HttpResponse<String> resp2 = post(baseUrl, client, req, key);
            assertEquals(200, resp2.statusCode());
            String task2 = JacksonMapper.MAPPER.readTree(resp2.body()).path("task_id").asText();
            assertEquals(task1, task2);
        });
    }

    @Test
    @DisplayName("returns 409 IDEMPOTENCY_CONFLICT for same key with different payload")
    void returns409ForIdempotencyConflict() throws Exception {
        withServer((baseUrl, client) -> {
            String key = "repo:1:sha:simple";

            ObjectNode req1 = baseRequest();
            HttpResponse<String> resp1 = post(baseUrl, client, req1, key);
            assertEquals(202, resp1.statusCode());

            ObjectNode req2 = baseRequest();
            req2.put("head_sha", "different-sha");
            HttpResponse<String> resp2 = post(baseUrl, client, req2, key);
            assertEquals(409, resp2.statusCode());

            JsonNode body = JacksonMapper.MAPPER.readTree(resp2.body());
            assertEquals("IDEMPOTENCY_CONFLICT", body.path("code").asText());
        });
    }

    @Test
    @DisplayName("returns 404 TASK_NOT_FOUND when querying unknown task status")
    void returns404WhenTaskStatusNotFound() throws Exception {
        withServer((baseUrl, client) -> {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/v1/orchestrator/reviews/not-exists"))
                            .timeout(Duration.ofSeconds(10))
                            .header("X-Trace-Id", UUID.randomUUID().toString())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(404, resp.statusCode());
            JsonNode body = JacksonMapper.MAPPER.readTree(resp.body());
            assertEquals("TASK_NOT_FOUND", body.path("code").asText());
        });
    }

    @Test
    @DisplayName("returns 404 TASK_NOT_FOUND when querying unknown task result")
    void returns404WhenTaskResultNotFound() throws Exception {
        withServer((baseUrl, client) -> {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/v1/orchestrator/reviews/not-exists/result"))
                            .timeout(Duration.ofSeconds(10))
                            .header("X-Trace-Id", UUID.randomUUID().toString())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(404, resp.statusCode());
            JsonNode body = JacksonMapper.MAPPER.readTree(resp.body());
            assertEquals("TASK_NOT_FOUND", body.path("code").asText());
        });
    }

    private ObjectNode baseRequest() {
        ObjectNode req = JacksonMapper.MAPPER.createObjectNode();
        req.put("mode", "SIMPLE");
        req.put("project_dir", tempDir.toString());
        req.put("tool_server_url", "http://localhost:9090");
        req.put("repo_name", "owner/repo");
        req.put("pr_number", 1);
        req.put("head_sha", "abc123");

        ArrayNode entries = req.putArray("diff_entries");
        ObjectNode entry = JacksonMapper.MAPPER.createObjectNode();
        entry.put("file_path", "src/A.java");
        entry.put("content", "@@ -1 +1 @@");
        entry.put("token_count", 10);
        entries.add(entry);

        ArrayNode allowed = req.putArray("allowed_files");
        allowed.add("src/A.java");
        return req;
    }

    private HttpResponse<String> post(String baseUrl, HttpClient client, ObjectNode req, String idempotencyKey)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/orchestrator/reviews"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Trace-Id", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(req.toString()));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.header("X-Idempotency-Key", idempotencyKey);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void withServer(ServerAssertion assertion) throws Exception {
        int port = randomPort();
        ReviewConfig config = new ReviewConfig();
        ReviewOrchestratorServer server = new ReviewOrchestratorServer(config);
        server.start(port);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            assertion.run("http://localhost:" + port, client);
        } finally {
            server.stop();
        }
    }

    private static int randomPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @FunctionalInterface
    private interface ServerAssertion {
        void run(String baseUrl, HttpClient client) throws Exception;
    }
}
