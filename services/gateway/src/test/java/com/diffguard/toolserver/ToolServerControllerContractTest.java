package com.diffguard.toolserver;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ToolServerController Contract")
class ToolServerControllerContractTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("health endpoint should return OK")
    void healthEndpointShouldReturnOk() throws Exception {
        withServer((baseUrl, client) -> {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/health"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, resp.statusCode());
            assertEquals("OK", resp.body());
        });
    }

    @Test
    @DisplayName("file-content should fail when session header missing")
    void fileContentShouldFailWhenSessionHeaderMissing() throws Exception {
        withServer((baseUrl, client) -> {
            HttpResponse<String> resp = postJson(client, baseUrl + "/api/v1/tools/file-content", "{\"file_path\":\"src/A.java\"}", null);
            assertEquals(200, resp.statusCode());
            JsonNode body = JacksonMapper.MAPPER.readTree(resp.body());
            assertFalse(body.path("success").asBoolean());
            assertTrue(body.path("error").asText().contains("Missing X-Session-Id"));
        });
    }

    @Test
    @DisplayName("supports create session, call file-content, then delete session")
    void supportsCreateCallAndDeleteSession() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/A.java"), "class A {}");

        withServer((baseUrl, client) -> {
            ObjectNode createReq = JacksonMapper.MAPPER.createObjectNode();
            createReq.put("project_dir", tempDir.toString());
            ArrayNode diffEntries = createReq.putArray("diff_entries");
            ObjectNode entry = JacksonMapper.MAPPER.createObjectNode();
            entry.put("file_path", "src/A.java");
            entry.put("content", "@@ -0,0 +1 @@");
            entry.put("token_count", 10);
            diffEntries.add(entry);
            ArrayNode allowedFiles = createReq.putArray("allowed_files");
            allowedFiles.add("src/A.java");

            HttpResponse<String> createResp = postJson(client, baseUrl + "/api/v1/tools/session", createReq.toString(), null);
            assertEquals(200, createResp.statusCode());
            JsonNode created = JacksonMapper.MAPPER.readTree(createResp.body());
            assertTrue(created.path("success").asBoolean());
            String sessionId = created.path("session_id").asText();
            assertFalse(sessionId.isBlank());

            HttpResponse<String> fileResp = postJson(client, baseUrl + "/api/v1/tools/file-content",
                    "{\"file_path\":\"src/A.java\"}", sessionId);
            assertEquals(200, fileResp.statusCode());
            JsonNode fileBody = JacksonMapper.MAPPER.readTree(fileResp.body());
            assertTrue(fileBody.path("success").asBoolean());
            assertTrue(fileBody.path("result").asText().contains("class A {}"));

            HttpResponse<String> deleteResp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/v1/tools/session/" + sessionId))
                            .timeout(Duration.ofSeconds(5))
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, deleteResp.statusCode());

            HttpResponse<String> afterDeleteResp = postJson(client, baseUrl + "/api/v1/tools/file-content",
                    "{\"file_path\":\"src/A.java\"}", sessionId);
            JsonNode afterDelete = JacksonMapper.MAPPER.readTree(afterDeleteResp.body());
            assertFalse(afterDelete.path("success").asBoolean());
            assertTrue(afterDelete.path("error").asText().contains("Session not found"));
        });
    }

    private HttpResponse<String> postJson(HttpClient client, String url, String body, String sessionId)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            builder.header("X-Session-Id", sessionId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void withServer(ServerAssertion assertion) throws Exception {
        int port = randomPort();
        ToolServerApp server = new ToolServerApp(new ReviewConfig());
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
