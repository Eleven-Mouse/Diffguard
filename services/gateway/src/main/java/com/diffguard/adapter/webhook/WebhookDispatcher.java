package com.diffguard.adapter.webhook;

import com.diffguard.infrastructure.common.JacksonMapper;
import com.diffguard.infrastructure.config.ReviewConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Forwards webhook PR info to the Python agent service for review.
 * No database, no message queue — just HTTP proxy.
 */
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final String pythonAgentUrl;
    private final ReviewConfig config;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    public WebhookDispatcher(ReviewConfig config) {
        this.config = config;
        String url = "http://localhost:8000";
        if (config.getAgentService() != null) {
            url = config.getAgentService().getUrl();
        }
        this.pythonAgentUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Dispatch a review request asynchronously to the Python service.
     */
    public void dispatchAsync(GitHubPayloadParser.ParsedPullRequest pr) {
        String requestId = java.util.UUID.randomUUID().toString();
        CompletableFuture.runAsync(() -> {
            try {
                dispatch(pr, requestId);
            } catch (Exception e) {
                log.error("Failed to dispatch review for {}/pull/{}: {}",
                        pr.getRepoFullName(), pr.getPrNumber(), e.getMessage(), e);
            }
        }, executor);
    }

    void dispatch(GitHubPayloadParser.ParsedPullRequest pr, String requestId) throws Exception {
        ObjectNode request = JacksonMapper.MAPPER.createObjectNode();
        request.put("request_id", requestId);
        request.put("repo_full_name", pr.getRepoFullName());
        request.put("pr_number", pr.getPrNumber());
        request.put("head_sha", pr.getHeadSha());

        // GitHub token env var name
        String githubTokenEnv = "DIFFGUARD_GITHUB_TOKEN";
        if (config.getWebhook() != null) {
            // Use the webhook config's token env
            githubTokenEnv = config.getWebhook().getGithubTokenEnv();
        }
        request.put("github_token_env", githubTokenEnv);

        // LLM config
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
        request.set("review_config", reviewNode);

        // Tool server URL — resolve to an address the Python agent can reach
        String toolServerUrl = "";
        if (config.getAgentService() != null) {
            toolServerUrl = config.getAgentService().resolveToolServerUrl();
        }
        request.put("tool_server_url", toolServerUrl);

        String jsonBody = JacksonMapper.MAPPER.writeValueAsString(request);
        log.info("Dispatching webhook review {} to Python: {}/pull/{}",
                requestId, pr.getRepoFullName(), pr.getPrNumber());

        int timeoutSeconds = llm.getTimeoutSeconds() + 60;
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(pythonAgentUrl + "/api/v1/webhook-review"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            log.info("Review {} completed successfully", requestId);
        } else {
            log.warn("Review {} returned HTTP {}: {}", requestId, response.statusCode(),
                    response.body().substring(0, Math.min(500, response.body().length())));
        }
    }

    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
