package com.diffguard.domain.agent.python;

import com.diffguard.infrastructure.common.JacksonMapper;
import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.adapter.toolserver.model.DiffFileEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * HTTP 客户端，向 Python Agent 服务发送审查请求并解析响应。
 */
public class PythonAgentClient {

    private static final Logger log = LoggerFactory.getLogger(PythonAgentClient.class);
    private final String agentUrl;
    private final HttpClient httpClient;

    public PythonAgentClient(String agentUrl) {
        this.agentUrl = agentUrl.endsWith("/") ? agentUrl.substring(0, agentUrl.length() - 1) : agentUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 发送审查请求到 Python Agent 服务。
     *
     * @param mode        审查模式 (PIPELINE / MULTI_AGENT)
     * @param config      审查配置
     * @param diffEntries 差异文件列表
     * @param projectDir  项目目录
     * @param toolServerUrl Java 工具服务 URL
     * @return Python 返回的 JSON 响应
     */
    public JsonNode sendReview(String mode, ReviewConfig config,
                               List<DiffFileEntry> diffEntries, String projectDir,
                               String toolServerUrl) throws Exception {
        String requestId = java.util.UUID.randomUUID().toString();

        ObjectNode request = JacksonMapper.MAPPER.createObjectNode();
        request.put("request_id", requestId);
        request.put("mode", mode);
        request.put("project_dir", projectDir);
        request.put("tool_server_url", toolServerUrl);

        // Diff entries
        ArrayNode entriesArray = JacksonMapper.MAPPER.createArrayNode();
        for (DiffFileEntry entry : diffEntries) {
            ObjectNode e = JacksonMapper.MAPPER.createObjectNode();
            e.put("file_path", entry.getFilePath());
            e.put("content", entry.getContent());
            e.put("token_count", entry.getTokenCount());
            entriesArray.add(e);
        }
        request.set("diff_entries", entriesArray);

        // LLM config — 传递环境变量名而非明文密钥
        ReviewConfig.LlmConfig llm = config.getLlm();
        ObjectNode llmNode = JacksonMapper.MAPPER.createObjectNode();
        llmNode.put("provider", llm.getProvider());
        llmNode.put("model", llm.getModel());
        llmNode.put("api_key_env", llm.getApiKeyEnv());
        llmNode.put("base_url", llm.resolveBaseUrl());
        llmNode.put("max_tokens", llm.getMaxTokens());
        llmNode.put("temperature", llm.getTemperature());
        llmNode.put("timeout_seconds", llm.getTimeoutSeconds());
        request.set("llm_config", llmNode);

        // Review config
        ObjectNode reviewNode = JacksonMapper.MAPPER.createObjectNode();
        reviewNode.put("language", config.getReview().getLanguage());
        if (config.getRules() != null && config.getRules().getEnabled() != null) {
            ArrayNode rulesArray = JacksonMapper.MAPPER.createArrayNode();
            config.getRules().getEnabled().forEach(rulesArray::add);
            reviewNode.set("rules_enabled", rulesArray);
        }
        request.set("review_config", reviewNode);

        // Allowed files
        ArrayNode allowedArray = JacksonMapper.MAPPER.createArrayNode();
        diffEntries.stream().map(DiffFileEntry::getFilePath).forEach(allowedArray::add);
        request.set("allowed_files", allowedArray);

        String jsonBody = JacksonMapper.MAPPER.writeValueAsString(request);
        log.info("Sending {} review request {} to Python agent", mode, requestId);
        log.debug("Request body size: {} bytes", jsonBody.length());

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(agentUrl + "/api/v1/review"))
                .header("Content-Type", "application/json")
                .header("X-Trace-Id", requestId)
                .header("X-Request-Id", requestId)
                .timeout(Duration.ofSeconds(llm.getTimeoutSeconds() + 30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Python agent returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode result = JacksonMapper.MAPPER.readTree(response.body());
        log.info("Python agent response: status={}, issues={}",
                result.path("status").asText(),
                result.path("issues").size());
        return result;
    }
}
