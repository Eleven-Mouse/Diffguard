package com.diffguard.review;

import com.diffguard.review.model.DiffFileEntry;
import com.diffguard.review.model.ReviewIssue;
import com.diffguard.review.model.ReviewResult;
import com.diffguard.review.model.Severity;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.platform.common.JacksonMapper;
import com.diffguard.platform.config.ReviewConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Gateway -> Orchestrator 远程调用客户端。
 */
public class OrchestratorClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorClient.class);

    private final ReviewConfig config;
    private final HttpClient client;

    public OrchestratorClient(ReviewConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public ReviewResult review(Path projectDir,
                               List<DiffFileEntry> diffEntries,
                               ReviewEngineFactory.EngineType engineType) throws DiffGuardException {
        ReviewConfig.OrchestratorConfig oc = config.getOrchestrator();
        if (oc == null) {
            throw new DiffGuardException("orchestrator config missing");
        }
        String baseUrl = oc.resolveUrl();
        try {
            String taskId = createTask(baseUrl, projectDir, diffEntries, engineType);
            return pollResult(baseUrl, taskId, oc.getTimeoutSeconds(), oc.getPollIntervalMs());
        } catch (DiffGuardException e) {
            throw e;
        } catch (Exception e) {
            throw new DiffGuardException("orchestrator remote review failed", e);
        }
    }

    private String createTask(String baseUrl,
                              Path projectDir,
                              List<DiffFileEntry> diffEntries,
                              ReviewEngineFactory.EngineType engineType) throws Exception {
        ObjectNode request = JacksonMapper.MAPPER.createObjectNode();
        request.put("mode", engineType.name());
        request.put("project_dir", projectDir.toString());
        request.put("tool_server_url", ReviewEngineFactory.resolveToolServerUrl(config));

        ArrayNode entries = request.putArray("diff_entries");
        for (DiffFileEntry entry : diffEntries) {
            ObjectNode item = JacksonMapper.MAPPER.createObjectNode();
            item.put("file_path", entry.getFilePath());
            item.put("content", entry.getContent());
            item.put("token_count", entry.getTokenCount());
            entries.add(item);
        }

        String traceId = UUID.randomUUID().toString();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/orchestrator/reviews"))
                .header("Content-Type", "application/json")
                .header("X-Trace-Id", traceId)
                .header("X-Idempotency-Key", buildIdempotencyKey(projectDir, diffEntries, engineType))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(JacksonMapper.MAPPER.writeValueAsString(request)))
                .build();

        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new DiffGuardException("orchestrator create task failed: HTTP " + response.statusCode()
                    + " " + extractMessage(response.body()));
        }
        JsonNode body = JacksonMapper.MAPPER.readTree(response.body());
        String taskId = body.path("task_id").asText("");
        if (taskId.isBlank()) {
            throw new DiffGuardException("orchestrator create task returned empty task_id");
        }
        log.info("Orchestrator task submitted: {}", taskId);
        return taskId;
    }

    private ReviewResult pollResult(String baseUrl,
                                    String taskId,
                                    int timeoutSeconds,
                                    int pollIntervalMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/orchestrator/reviews/" + taskId + "/result"))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 202) {
                Thread.sleep(Math.max(200, pollIntervalMs));
                continue;
            }
            if (response.statusCode() >= 500) {
                JsonNode err = JacksonMapper.MAPPER.readTree(response.body());
                throw new DiffGuardException("orchestrator task failed: " + err.path("error").asText("unknown"));
            }
            if (response.statusCode() >= 400) {
                throw new DiffGuardException("orchestrator result query failed: HTTP " + response.statusCode()
                        + " " + extractMessage(response.body()));
            }
            if (response.statusCode() == 200) {
                JsonNode body = JacksonMapper.MAPPER.readTree(response.body());
                return parseResult(body);
            }
            throw new DiffGuardException("orchestrator result query failed: HTTP " + response.statusCode()
                    + " " + extractMessage(response.body()));
        }
        throw new DiffGuardException("orchestrator result timeout after " + timeoutSeconds + "s");
    }

    private ReviewResult parseResult(JsonNode body) {
        ReviewResult result = new ReviewResult();
        JsonNode issues = body.path("issues");
        if (issues.isArray()) {
            for (JsonNode i : issues) {
                ReviewIssue issue = new ReviewIssue();
                issue.setSeverity(Severity.fromString(i.path("severity").asText("INFO")));
                issue.setFile(i.path("file").asText(""));
                issue.setLine(i.path("line").asInt(0));
                issue.setType(i.path("type").asText(""));
                issue.setMessage(i.path("message").asText(""));
                issue.setSuggestion(i.path("suggestion").asText(""));
                result.addIssue(issue);
            }
        }
        result.setTotalTokensUsed(body.path("total_tokens_used").asInt(0));
        result.setReviewDurationMs(body.path("review_duration_ms").asLong(0));
        result.setHasCriticalFlag(body.path("has_critical_flag").asBoolean(false));
        return result;
    }

    private static String buildIdempotencyKey(Path projectDir,
                                              List<DiffFileEntry> diffEntries,
                                              ReviewEngineFactory.EngineType engineType) {
        StringBuilder raw = new StringBuilder();
        raw.append(projectDir.toAbsolutePath()).append("|").append(engineType.name());
        for (DiffFileEntry entry : diffEntries) {
            raw.append("|").append(entry.getFilePath()).append("#")
                    .append(entry.getTokenCount()).append("#")
                    .append(sha256(entry.getContent()));
        }
        return "cli:" + sha256(raw.toString());
    }

    private static String extractMessage(String payload) {
        if (payload == null || payload.isBlank()) return "";
        try {
            JsonNode body = JacksonMapper.MAPPER.readTree(payload);
            String message = body.path("message").asText("");
            if (!message.isBlank()) return message;
            String error = body.path("error").asText("");
            if (!error.isBlank()) return error;
        } catch (Exception ignore) {
            // best effort
        }
        return "";
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
