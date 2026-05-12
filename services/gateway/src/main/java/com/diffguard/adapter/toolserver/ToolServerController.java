package com.diffguard.adapter.toolserver;

import com.diffguard.adapter.toolserver.ToolSessionManager.Session;
import com.diffguard.domain.agent.core.AgentTool;
import com.diffguard.domain.agent.core.ToolResult;
import com.diffguard.adapter.toolserver.model.DiffFileEntry;
import com.diffguard.infrastructure.common.JacksonMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Java 工具服务端点控制器。
 * <p>
 * 注册在 Javalin 上，为 Python Agent 提供文件读取、AST、调用图等工具的 HTTP 回调。
 * 每个请求通过 X-Session-Id 关联到对应的审查会话。
 */
public class ToolServerController {

    private static final Logger log = LoggerFactory.getLogger(ToolServerController.class);
    private static final String SESSION_HEADER = "X-Session-Id";
    private static final String TOOL_SECRET_HEADER = "X-Tool-Secret";

    private final String toolSecret = System.getenv("DIFFGUARD_TOOL_SECRET");

    private final ToolSessionManager sessionManager = new ToolSessionManager();

    /**
     * Release all session resources. Should be called on server shutdown.
     */
    public void close() {
        sessionManager.close();
        log.info("Tool server controller closed, all sessions released");
    }

    public void registerRoutes(Javalin app) {
        // Shared-secret authentication for all tool endpoints
        app.before(ctx -> {
            String path = ctx.path();
            if (!path.startsWith("/api/v1/tools/")) {
                return;
            }
            // Skip auth if no secret configured (backward compat for CLI mode)
            if (toolSecret == null || toolSecret.isEmpty()) {
                return;
            }
            String headerSecret = ctx.header(TOOL_SECRET_HEADER);
            if (!toolSecret.equals(headerSecret)) {
                ctx.status(401);
                ctx.json(errorResponse("Unauthorized: invalid or missing " + TOOL_SECRET_HEADER));
                return;
            }
        });

        app.post("/api/v1/tools/session", this::handleCreateSession);
        app.delete("/api/v1/tools/session/{sessionId}", this::handleDeleteSession);
        app.post("/api/v1/tools/file-content", this::handleFileContent);
        app.post("/api/v1/tools/diff-context", this::handleDiffContext);
        app.post("/api/v1/tools/method-definition", this::handleMethodDefinition);
        app.post("/api/v1/tools/call-graph", this::handleCallGraph);
        app.post("/api/v1/tools/related-files", this::handleRelatedFiles);
        app.post("/api/v1/tools/semantic-search", this::handleSemanticSearch);
        log.info("Tool server endpoints registered");
    }

    private void handleCreateSession(Context ctx) {
        try {
            JsonNode body = ctx.bodyAsClass(JsonNode.class);
            String sessionId = UUID.randomUUID().toString();
            String projectDir = body.path("project_dir").asText();
            Set<String> allowedFiles = Set.of();
            if (body.has("allowed_files")) {
                allowedFiles = StreamSupport.stream(body.path("allowed_files").spliterator(), false)
                        .map(JsonNode::asText).collect(Collectors.toSet());
            }

            List<DiffFileEntry> diffEntries = parseDiffEntries(body.path("diff_entries"));

            sessionManager.create(sessionId, Path.of(projectDir), diffEntries, allowedFiles);
            log.info("Tool session created: {}", sessionId);

            ObjectNode response = successResponse();
            response.put("session_id", sessionId);
            ctx.json(response);
        } catch (Exception e) {
            log.error("Failed to create session", e);
            ctx.json(errorResponse(e.getMessage()));
        }
    }

    private void handleDeleteSession(Context ctx) {
        String sessionId = ctx.pathParam("sessionId");
        sessionManager.remove(sessionId);
        ctx.json(successResponse());
    }

    private void handleFileContent(Context ctx) { dispatchTool(ctx, "get_file_content", "file_path"); }
    private void handleDiffContext(Context ctx) { dispatchTool(ctx, "get_diff_context", "query"); }
    private void handleMethodDefinition(Context ctx) { dispatchTool(ctx, "get_method_definition", "file_path"); }
    private void handleCallGraph(Context ctx) { dispatchTool(ctx, "get_call_graph", "query"); }
    private void handleRelatedFiles(Context ctx) { dispatchTool(ctx, "get_related_files", "query"); }
    private void handleSemanticSearch(Context ctx) { dispatchTool(ctx, "semantic_search", "query"); }

    private void dispatchTool(Context ctx, String toolName, String inputField) {
        String sessionId = ctx.header(SESSION_HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            ctx.json(errorResponse("Missing X-Session-Id header"));
            return;
        }

        Session session = sessionManager.get(sessionId);
        if (session == null) {
            ctx.json(errorResponse("Session not found or expired: " + sessionId));
            return;
        }

        try {
            JsonNode body = ctx.bodyAsClass(JsonNode.class);
            String input = getTextOrEmpty(body, inputField);

            AgentTool tool = session.getTool(toolName);
            if (tool == null) {
                ctx.json(errorResponse("Unknown tool: " + toolName));
                return;
            }

            ToolResult result = tool.execute(input, session.getContext());
            if (result.isSuccess()) {
                ctx.json(successResponse(result.getOutput()));
            } else {
                ctx.json(errorResponse(result.getError()));
            }
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolName, e);
            ctx.json(errorResponse(e.getMessage()));
        }
    }

    private static String getTextOrEmpty(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private List<DiffFileEntry> parseDiffEntries(JsonNode array) {
        if (array == null || !array.isArray()) return List.of();
        List<DiffFileEntry> entries = new java.util.ArrayList<>();
        for (JsonNode node : array) {
            String filePath = getTextOrEmpty(node, "file_path");
            String content = getTextOrEmpty(node, "content");
            int tokenCount = node.path("token_count").asInt(0);
            entries.add(new DiffFileEntry(filePath, content, tokenCount));
        }
        return entries;
    }

    private ObjectNode successResponse() { return successResponse(null); }

    private ObjectNode successResponse(String result) {
        ObjectNode node = JacksonMapper.MAPPER.createObjectNode();
        node.put("success", true);
        if (result != null) node.put("result", result);
        return node;
    }

    private ObjectNode errorResponse(String error) {
        ObjectNode node = JacksonMapper.MAPPER.createObjectNode();
        node.put("success", false);
        if (error != null) node.put("error", error);
        return node;
    }
}
