package com.diffguard.orchestrator;

import com.diffguard.orchestrator.dto.ApiErrorResponse;
import com.diffguard.orchestrator.dto.DiffEntryDto;
import com.diffguard.orchestrator.dto.IssueResponseDto;
import com.diffguard.orchestrator.dto.ReviewTaskCreateRequest;
import com.diffguard.orchestrator.dto.ReviewTaskCreateResponse;
import com.diffguard.orchestrator.dto.ReviewTaskResultResponse;
import com.diffguard.orchestrator.dto.ReviewTaskStatusResponse;
import com.diffguard.review.ast.ASTEnricher;
import com.diffguard.review.ReviewEngine;
import com.diffguard.review.model.DiffFileEntry;
import com.diffguard.review.model.ReviewIssue;
import com.diffguard.review.model.ReviewResult;
import com.diffguard.review.model.Severity;
import com.diffguard.review.rules.RuleEngine;
import com.diffguard.platform.common.JacksonMapper;
import com.diffguard.platform.config.ReviewConfig;
import com.diffguard.platform.messaging.RabbitMQConfig;
import com.diffguard.platform.messaging.ReviewTaskMessage;
import com.diffguard.platform.messaging.ReviewTaskPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Delivery;
import com.diffguard.review.ReviewEngineFactory;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * review-orchestrator-service 骨架实现。
 * 对外提供任务创建、状态查询、结果查询三个接口。
 */
public class ReviewOrchestratorServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestratorServer.class);

    private final ReviewConfig config;
    private final RuleEngine ruleEngine = new RuleEngine();
    private final Javalin app;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReviewResult> results = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> taskRequestHash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> requestTaskIndex = new ConcurrentHashMap<>();
    private final RabbitMQConfig rabbitMqConfig;
    private final ReviewTaskPublisher taskPublisher;
    private final Channel resultConsumerChannel;
    private volatile String resultConsumerTag;

    public ReviewOrchestratorServer(ReviewConfig config) {
        this.config = config;
        this.executor = new ThreadPoolExecutor(
                2, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                new ThreadPoolExecutor.CallerRunsPolicy());

        this.app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = 20_000_000L;
        });
        registerRoutes();

        RabbitMQConfig mqConfig = null;
        ReviewTaskPublisher publisher = null;
        Channel consumerChannel = null;
        if (isMessageQueueEnabled()) {
            try {
                ReviewConfig.MessageQueueConfig mq = config.getMessageQueue();
                mqConfig = new RabbitMQConfig(
                        mq.resolveHost(),
                        mq.resolvePort(),
                        resolveMqUser(mq),
                        mq.resolvePassword()
                );
                publisher = new ReviewTaskPublisher(mqConfig);
                consumerChannel = mqConfig.getConnection().createChannel();
                startResultConsumer(consumerChannel);
                log.info("Orchestrator MQ mode enabled: {}:{} queue={}",
                        mq.resolveHost(), mq.resolvePort(), RabbitMQConfig.RESULT_QUEUE);
            } catch (Exception e) {
                log.warn("Orchestrator MQ init failed, fallback to local sync execution: {}", e.getMessage());
                closeQuietly(consumerChannel);
                closeQuietly(publisher);
                closeQuietly(mqConfig);
                mqConfig = null;
                publisher = null;
                consumerChannel = null;
            }
        }
        this.rabbitMqConfig = mqConfig;
        this.taskPublisher = publisher;
        this.resultConsumerChannel = consumerChannel;
    }

    private void registerRoutes() {
        app.get("/health", ctx -> ctx.result("OK"));
        app.post("/api/v1/orchestrator/reviews", this::handleCreateReviewTask);
        app.get("/api/v1/orchestrator/reviews/{taskId}", this::handleGetTaskStatus);
        app.get("/api/v1/orchestrator/reviews/{taskId}/result", this::handleGetTaskResult);
        log.info("Orchestrator endpoints registered");
    }

    public void start(int port) {
        app.start(port);
        log.info("DiffGuard Orchestrator 服务已启动，端口：{}", port);
        log.info("  POST /api/v1/orchestrator/reviews");
        log.info("  GET  /api/v1/orchestrator/reviews/{taskId}");
        log.info("  GET  /api/v1/orchestrator/reviews/{taskId}/result");
    }

    public void stop() {
        close();
    }

    private void handleCreateReviewTask(Context ctx) {
        String traceId = resolveTraceId(ctx);
        try {
            ReviewTaskCreateRequest req = ctx.bodyAsClass(ReviewTaskCreateRequest.class);
            String mode = req.mode == null ? "" : req.mode.trim();
            String projectDir = req.projectDir == null ? "" : req.projectDir.trim();
            if (req.toolServerUrl == null || req.toolServerUrl.isBlank()) {
                writeError(ctx, 422, "MISSING_TOOL_SERVER_URL", "tool_server_url is required", traceId);
                return;
            }

            if (mode.isBlank()) {
                writeError(ctx, 400, "INVALID_REQUEST", "mode is required", traceId);
                return;
            }
            if (!isSupportedMode(mode)) {
                writeError(ctx, 400, "INVALID_REQUEST", "unsupported mode: " + mode, traceId);
                return;
            }

            List<DiffFileEntry> diffEntries = parseDiffEntries(req.diffEntries);
            if (diffEntries.isEmpty()) {
                writeError(ctx, 400, "INVALID_REQUEST", "diff_entries is required and cannot be empty", traceId);
                return;
            }
            List<String> allowedFiles = parseAllowedFiles(req.allowedFiles, diffEntries);

            Path workDir = projectDir.isBlank()
                    ? Path.of("").toAbsolutePath()
                    : Path.of(projectDir);

            String idempotencyKey = headerOrBlank(ctx, "X-Idempotency-Key");
            String requestHash = buildRequestHash(mode, workDir, req.toolServerUrl, diffEntries,
                    req.repoName, req.prNumber, req.headSha, allowedFiles);
            if (!idempotencyKey.isBlank()) {
                String existingTaskId = idempotencyIndex.get(idempotencyKey);
                if (existingTaskId != null) {
                    String existingHash = taskRequestHash.get(existingTaskId);
                    if (existingHash != null && !existingHash.equals(requestHash)) {
                        writeError(ctx, 409, "IDEMPOTENCY_CONFLICT",
                                "same idempotency key with different request payload", traceId);
                        return;
                    }
                    TaskState existingState = tasks.get(existingTaskId);
                    if (existingState != null) {
                        ReviewTaskCreateResponse resp = new ReviewTaskCreateResponse();
                        resp.taskId = existingTaskId;
                        resp.status = existingState.status;
                        resp.reviewMode = existingState.mode.toUpperCase();
                        resp.createdAt = existingState.createdAt;
                        ctx.status(200).json(resp);
                        return;
                    }
                }
            }

            String taskId = UUID.randomUUID().toString();
            TaskState state = TaskState.pending(taskId, mode);
            tasks.put(taskId, state);
            taskRequestHash.put(taskId, requestHash);
            if (!idempotencyKey.isBlank()) {
                idempotencyIndex.putIfAbsent(idempotencyKey, taskId);
            }

            boolean asyncDispatched = tryDispatchViaMq(taskId, mode, workDir, diffEntries,
                    req.toolServerUrl, allowedFiles);
            if (!asyncDispatched) {
                executor.submit(() -> runTask(taskId, mode, workDir, diffEntries));
            }

            ReviewTaskCreateResponse resp = new ReviewTaskCreateResponse();
            resp.taskId = taskId;
            resp.status = tasks.get(taskId) != null ? tasks.get(taskId).status : "PENDING";
            resp.reviewMode = mode.toUpperCase();
            resp.createdAt = state.createdAt;
            ctx.status(202).json(resp);
        } catch (IllegalArgumentException e) {
            log.warn("Create review task invalid request: {}", e.getMessage());
            writeError(ctx, 400, "INVALID_REQUEST", e.getMessage(), traceId);
        } catch (Exception e) {
            log.error("Create review task failed", e);
            writeError(ctx, 500, "INTERNAL_ERROR", "failed to create review task: " + e.getMessage(), traceId);
        }
    }

    private void runTask(String taskId, String mode, Path projectDir, List<DiffFileEntry> rawEntries) {
        TaskState state = tasks.get(taskId);
        if (state == null) return;
        state.markRunning();
        try {
            List<DiffFileEntry> entries = enrichSafely(projectDir, rawEntries);
            List<ReviewIssue> staticIssues = ruleEngine.scan(entries);

            ReviewEngineFactory.EngineType engineType = parseEngineType(mode);
            ReviewResult result;
            try (ReviewEngine engine = ReviewEngineFactory.create(engineType, config, projectDir, entries, false)) {
                result = engine.review(entries, projectDir);
            }
            for (ReviewIssue issue : staticIssues) {
                result.addIssue(issue);
            }

            results.put(taskId, result);
            state.markCompleted();
            log.info("Orchestrator task completed: {} (issues={})", taskId, result.getIssues().size());
        } catch (Exception e) {
            state.markFailed(e.getMessage());
            log.error("Orchestrator task failed: {}", taskId, e);
        }
    }

    private List<DiffFileEntry> enrichSafely(Path projectDir, List<DiffFileEntry> entries) {
        try {
            return new ASTEnricher(projectDir, config).enrich(entries);
        } catch (Exception e) {
            log.warn("AST enrich failed, fallback to raw diff: {}", e.getMessage());
            return entries;
        }
    }

    private void handleGetTaskStatus(Context ctx) {
        String traceId = resolveTraceId(ctx);
        String taskId = ctx.pathParam("taskId");
        TaskState state = tasks.get(taskId);
        if (state == null) {
            writeError(ctx, 404, "TASK_NOT_FOUND", "task not found: " + taskId, traceId);
            return;
        }

        ReviewTaskStatusResponse resp = new ReviewTaskStatusResponse();
        resp.taskId = state.taskId;
        resp.status = state.status;
        resp.error = state.errorMessage;
        resp.startedAt = state.startedAt;
        resp.completedAt = state.completedAt;
        ctx.json(resp);
    }

    private void handleGetTaskResult(Context ctx) {
        String traceId = resolveTraceId(ctx);
        String taskId = ctx.pathParam("taskId");
        TaskState state = tasks.get(taskId);
        if (state == null) {
            writeError(ctx, 404, "TASK_NOT_FOUND", "task not found: " + taskId, traceId);
            return;
        }
        if (!"COMPLETED".equals(state.status)) {
            ReviewTaskResultResponse pending = new ReviewTaskResultResponse();
            pending.taskId = taskId;
            pending.status = state.status.toLowerCase();
            pending.error = state.errorMessage;
            ctx.status("FAILED".equals(state.status) ? 500 : 202).json(pending);
            return;
        }

        ReviewResult result = results.get(taskId);
        if (result == null) {
            writeError(ctx, 404, "RESULT_NOT_FOUND", "result not found: " + taskId, traceId);
            return;
        }

        ReviewTaskResultResponse resp = new ReviewTaskResultResponse();
        resp.taskId = taskId;
        resp.status = "completed";
        resp.hasCriticalFlag = result.hasCriticalIssues();
        resp.totalTokensUsed = result.getTotalTokensUsed();
        resp.reviewDurationMs = result.getReviewDurationMs();
        resp.summary = result.getSummary(config.getReview().getLanguage());

        for (ReviewIssue issue : result.getIssues()) {
            IssueResponseDto item = new IssueResponseDto();
            item.severity = issue.getSeverity().name();
            item.file = issue.getFile();
            item.line = issue.getLine();
            item.type = issue.getType();
            item.message = issue.getMessage();
            item.suggestion = issue.getSuggestion();
            resp.issues.add(item);
        }
        ctx.json(resp);
    }

    private static ReviewEngineFactory.EngineType parseEngineType(String mode) {
        String m = mode == null ? "" : mode.trim().toUpperCase();
        return switch (m) {
            case "PIPELINE" -> ReviewEngineFactory.EngineType.PIPELINE;
            case "MULTI_AGENT" -> ReviewEngineFactory.EngineType.MULTI_AGENT;
            case "SIMPLE" -> ReviewEngineFactory.EngineType.SIMPLE;
            default -> throw new IllegalArgumentException("unsupported mode: " + mode);
        };
    }

    private List<DiffFileEntry> parseDiffEntries(List<DiffEntryDto> dtos) {
        if (dtos == null || dtos.isEmpty()) return List.of();
        List<DiffFileEntry> entries = new java.util.ArrayList<>();
        for (DiffEntryDto dto : dtos) {
            String filePath = dto.filePath == null ? "" : dto.filePath.trim();
            String content = dto.content == null ? "" : dto.content;
            int tokenCount = dto.tokenCount;
            if (filePath.isBlank()) continue;
            entries.add(new DiffFileEntry(filePath, content, tokenCount));
        }
        return entries;
    }

    private static String resolveTraceId(Context ctx) {
        String traceId = ctx.header("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        return traceId;
    }

    private static String headerOrBlank(Context ctx, String headerName) {
        String v = ctx.header(headerName);
        return v == null ? "" : v.trim();
    }

    private static void writeError(Context ctx, int status, String code, String message, String traceId) {
        ctx.status(status).json(ApiErrorResponse.of(code, message, traceId));
    }

    private static String buildRequestHash(String mode, Path projectDir, String toolServerUrl,
                                           List<DiffFileEntry> entries,
                                           String repoName, Integer prNumber,
                                           String headSha, List<String> allowedFiles) {
        StringBuilder raw = new StringBuilder();
        raw.append(mode.toUpperCase()).append("|").append(projectDir.toAbsolutePath())
                .append("|").append(nullToEmpty(toolServerUrl))
                .append("|").append(nullToEmpty(repoName))
                .append("|").append(prNumber == null ? "" : prNumber)
                .append("|").append(nullToEmpty(headSha));
        for (DiffFileEntry e : entries) {
            raw.append("|").append(e.getFilePath()).append("#").append(e.getTokenCount())
                    .append("#").append(sha256(e.getContent()));
        }
        if (allowedFiles != null) {
            for (String file : allowedFiles) {
                raw.append("|allow:").append(file);
            }
        }
        return sha256(raw.toString());
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
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public void close() {
        app.stop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        closeQuietly(taskPublisher);
        if (resultConsumerChannel != null) {
            try {
                if (resultConsumerTag != null && resultConsumerChannel.isOpen()) {
                    resultConsumerChannel.basicCancel(resultConsumerTag);
                }
            } catch (Exception e) {
                log.debug("Failed to cancel result consumer: {}", e.getMessage());
            }
        }
        closeQuietly(resultConsumerChannel);
        closeQuietly(rabbitMqConfig);
    }

    private boolean tryDispatchViaMq(String taskId,
                                     String mode,
                                     Path projectDir,
                                     List<DiffFileEntry> diffEntries,
                                     String toolServerUrl,
                                     List<String> allowedFiles) {
        if (!shouldUseMq(mode) || taskPublisher == null) {
            return false;
        }
        try {
            String normalizedMode = normalizeMode(mode);
            ReviewTaskMessage message = new ReviewTaskMessage(
                    taskId,
                    normalizedMode,
                    config,
                    diffEntries,
                    projectDir.toString(),
                    toolServerUrl,
                    allowedFiles,
                    false
            );
            taskPublisher.publish(message);
            requestTaskIndex.put(taskId, taskId);
            TaskState state = tasks.get(taskId);
            if (state != null) {
                state.markRunning();
            }
            log.info("Orchestrator task dispatched via MQ: taskId={}, mode={}, routingKey={}",
                    taskId, normalizedMode, message.getRoutingKey());
            return true;
        } catch (Exception e) {
            log.warn("MQ dispatch failed, fallback to local sync run. taskId={}, mode={}, error={}",
                    taskId, mode, e.getMessage());
            return false;
        }
    }

    private boolean shouldUseMq(String mode) {
        if (!isMessageQueueEnabled() || taskPublisher == null) {
            return false;
        }
        String normalized = normalizeMode(mode);
        return "PIPELINE".equals(normalized) || "MULTI_AGENT".equals(normalized);
    }

    private void startResultConsumer(Channel channel) throws IOException {
        channel.basicQos(10);
        this.resultConsumerTag = channel.basicConsume(
                RabbitMQConfig.RESULT_QUEUE,
                false,
                this::handleResultDelivery,
                tag -> log.warn("Result consumer cancelled by broker: {}", tag)
        );
        log.info("Orchestrator result consumer started on queue {}", RabbitMQConfig.RESULT_QUEUE);
    }

    private void handleResultDelivery(String consumerTag, Delivery delivery) throws IOException {
        long deliveryTag = delivery.getEnvelope().getDeliveryTag();
        try {
            JsonNode root = JacksonMapper.MAPPER.readTree(delivery.getBody());
            String taskId = resolveResultTaskId(root);
            if (taskId.isBlank()) {
                log.error("Drop invalid result message: missing task_id/request_id");
                resultConsumerChannel.basicNack(deliveryTag, false, false);
                return;
            }

            TaskState state = tasks.get(taskId);
            if (state == null) {
                log.warn("Ignore result for unknown task: {}", taskId);
                resultConsumerChannel.basicAck(deliveryTag, false);
                return;
            }

            String status = normalizeResultStatus(text(root.path("status")));
            if ("failed".equals(status)) {
                String error = text(root.path("error"));
                state.markFailed(error.isBlank() ? "agent returned failed status" : error);
                results.remove(taskId);
                requestTaskIndex.remove(taskId);
                log.warn("Orchestrator task failed from MQ result: taskId={}, error={}", taskId, state.errorMessage);
            } else {
                ReviewResult result = parseReviewResult(root);
                results.put(taskId, result);
                state.markCompleted();
                requestTaskIndex.remove(taskId);
                log.info("Orchestrator task completed from MQ result: taskId={}, issues={}",
                        taskId, result.getIssues().size());
            }
            resultConsumerChannel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Process MQ result failed: {}", e.getMessage(), e);
            resultConsumerChannel.basicNack(deliveryTag, false, false);
        }
    }

    private String resolveResultTaskId(JsonNode root) {
        String taskId = text(root.path("task_id"));
        if (!taskId.isBlank()) {
            return taskId;
        }
        String requestId = text(root.path("request_id"));
        if (requestId.isBlank()) {
            return "";
        }
        return requestTaskIndex.getOrDefault(requestId, requestId);
    }

    private static ReviewResult parseReviewResult(JsonNode root) {
        ReviewResult result = new ReviewResult();
        JsonNode issues = root.path("issues");
        if (issues.isArray()) {
            for (JsonNode issueNode : issues) {
                ReviewIssue issue = new ReviewIssue();
                issue.setSeverity(Severity.fromString(text(issueNode.path("severity"))));
                issue.setFile(text(issueNode.path("file")));
                issue.setLine(issueNode.path("line").asInt(0));
                issue.setType(text(issueNode.path("type")));
                issue.setMessage(text(issueNode.path("message")));
                issue.setSuggestion(text(issueNode.path("suggestion")));
                result.addIssue(issue);
            }
        }
        result.setHasCriticalFlag(root.path("has_critical_flag").asBoolean(false));
        result.setTotalTokensUsed(root.path("total_tokens_used").asInt(0));
        result.setReviewDurationMs(root.path("review_duration_ms").asLong(0));
        return result;
    }

    private static String normalizeResultStatus(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase();
        if ("failed".equals(v)) return "failed";
        return "completed";
    }

    private boolean isMessageQueueEnabled() {
        return config.getMessageQueue() != null && config.getMessageQueue().isEnabled();
    }

    private static String resolveMqUser(ReviewConfig.MessageQueueConfig mq) {
        String env = System.getenv("RABBITMQ_USER");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return mq.getUser();
    }

    private static String normalizeMode(String mode) {
        return mode == null ? "" : mode.trim().toUpperCase();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        String raw = node.asText("");
        return raw == null ? "" : raw.trim();
    }

    private static String nullToEmpty(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static boolean isSupportedMode(String mode) {
        String normalized = normalizeMode(mode);
        return "SIMPLE".equals(normalized) || "PIPELINE".equals(normalized)
                || "MULTI_AGENT".equals(normalized);
    }

    private static List<String> parseAllowedFiles(List<String> requestedAllowedFiles, List<DiffFileEntry> diffEntries) {
        if (requestedAllowedFiles == null || requestedAllowedFiles.isEmpty()) {
            return diffEntries.stream().map(DiffFileEntry::getFilePath).toList();
        }
        return requestedAllowedFiles.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignore) {
            // ignore
        }
    }

    private static class TaskState {
        final String taskId;
        final String mode;
        final long createdAt;
        volatile String status;
        volatile Long startedAt;
        volatile Long completedAt;
        volatile String errorMessage;

        private TaskState(String taskId, String mode) {
            this.taskId = taskId;
            this.mode = mode;
            this.createdAt = System.currentTimeMillis();
            this.status = "PENDING";
        }

        static TaskState pending(String taskId, String mode) {
            return new TaskState(taskId, mode);
        }

        void markRunning() {
            this.status = "RUNNING";
            this.startedAt = System.currentTimeMillis();
        }

        void markCompleted() {
            this.status = "COMPLETED";
            this.completedAt = System.currentTimeMillis();
        }

        void markFailed(String error) {
            this.status = "FAILED";
            this.errorMessage = error;
            this.completedAt = System.currentTimeMillis();
        }
    }
}
