package com.diffguard.infrastructure.messaging;

import com.diffguard.infrastructure.common.JacksonMapper;
import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.domain.review.model.DiffFileEntry;
import com.diffguard.service.ReviewEngineFactory.EngineType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

/**
 * Review 任务消息，在 Java → RabbitMQ → Python 之间传递。
 */
public class ReviewTaskMessage {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            JacksonMapper.MAPPER;

    private final String taskId;
    private final String mode;
    private final String projectDir;
    private final List<DiffFileEntry> diffEntries;
    private final ReviewConfig config;
    private final String toolServerUrl;
    private final boolean hotfix;
    private final long createdAt;

    public ReviewTaskMessage(String mode, ReviewConfig config,
                             List<DiffFileEntry> diffEntries, String projectDir,
                             String toolServerUrl, boolean hotfix) {
        this.taskId = UUID.randomUUID().toString();
        this.mode = mode;
        this.config = config;
        this.diffEntries = diffEntries;
        this.projectDir = projectDir;
        this.toolServerUrl = toolServerUrl;
        this.hotfix = hotfix;
        this.createdAt = System.currentTimeMillis();
    }

    public String getTaskId() { return taskId; }
    public String getMode() { return mode; }
    public String getProjectDir() { return projectDir; }
    public List<DiffFileEntry> getDiffEntries() { return diffEntries; }
    public boolean isHotfix() { return hotfix; }
    public long getCreatedAt() { return createdAt; }

    /**
     * 计算消息优先级: hotfix=9, 普通PR=5。
     */
    public int getPriority() {
        return hotfix ? 9 : 5;
    }

    /**
     * 计算 RabbitMQ routing key。
     */
    public String getRoutingKey() {
        return "review." + mode.toLowerCase() + ".task";
    }

    /**
     * 序列化为 JSON 字节数组（用于 RabbitMQ 消息体）。
     */
    public byte[] toJsonBytes() {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("task_id", taskId);
            root.put("mode", mode);
            root.put("project_dir", projectDir);
            root.put("tool_server_url", toolServerUrl);
            root.put("created_at", createdAt);

            // Diff entries
            ArrayNode entriesArray = MAPPER.createArrayNode();
            for (DiffFileEntry entry : diffEntries) {
                ObjectNode e = MAPPER.createObjectNode();
                e.put("file_path", entry.getFilePath());
                e.put("content", entry.getContent());
                e.put("token_count", entry.getTokenCount());
                entriesArray.add(e);
            }
            root.set("diff_entries", entriesArray);

            // LLM config
            ReviewConfig.LlmConfig llm = config.getLlm();
            ObjectNode llmNode = MAPPER.createObjectNode();
            llmNode.put("provider", llm.getProvider());
            llmNode.put("model", llm.getModel());
            llmNode.put("api_key_env", llm.getApiKeyEnv());
            llmNode.put("base_url", llm.resolveBaseUrl());
            llmNode.put("max_tokens", llm.getMaxTokens());
            llmNode.put("temperature", llm.getTemperature());
            llmNode.put("timeout_seconds", llm.getTimeoutSeconds());
            root.set("llm_config", llmNode);

            // Review config
            ObjectNode reviewNode = MAPPER.createObjectNode();
            reviewNode.put("language", config.getReview().getLanguage());
            if (config.getRules() != null && config.getRules().getEnabled() != null) {
                ArrayNode rulesArray = MAPPER.createArrayNode();
                config.getRules().getEnabled().forEach(rulesArray::add);
                reviewNode.set("rules_enabled", rulesArray);
            }
            root.set("review_config", reviewNode);

            // Allowed files
            ArrayNode allowedArray = MAPPER.createArrayNode();
            diffEntries.stream().map(DiffFileEntry::getFilePath).forEach(allowedArray::add);
            root.set("allowed_files", allowedArray);

            return MAPPER.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ReviewTaskMessage", e);
        }
    }

    /**
     * 从 JSON 字节数组反序列化。
     */
    public static ReviewTaskMessage fromJson(byte[] data) {
        try {
            JsonNode root = MAPPER.readTree(data);
            // Reconstruct minimal config
            ReviewConfig config = new ReviewConfig();
            ReviewConfig.LlmConfig llm = config.getLlm();
            JsonNode llmNode = root.path("llm_config");
            llm.setProvider(llmNode.path("provider").asText("openai"));
            llm.setModel(llmNode.path("model").asText("gpt-4o"));

            JsonNode entries = root.path("diff_entries");
            java.util.List<DiffFileEntry> diffEntries = new java.util.ArrayList<>();
            if (entries.isArray()) {
                for (JsonNode e : entries) {
                    diffEntries.add(new DiffFileEntry(
                            e.path("file_path").asText(""),
                            e.path("content").asText(""),
                            e.path("token_count").asInt(0)
                    ));
                }
            }

            return new ReviewTaskMessage(
                    root.path("mode").asText("PIPELINE"),
                    config,
                    diffEntries,
                    root.path("project_dir").asText(),
                    root.path("tool_server_url").asText("http://localhost:9090"),
                    false
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize ReviewTaskMessage", e);
        }
    }

    /**
     * 获取原始 taskId（从序列化消息恢复时使用）。
     */
    public static String extractTaskId(byte[] data) {
        try {
            if (data == null || data.length == 0) {
                return null;
            }
            String value = MAPPER.readTree(data).path("task_id").asText(null);
            return value != null && !value.isEmpty() ? value : null;
        } catch (Exception e) {
            return null;
        }
    }
}
