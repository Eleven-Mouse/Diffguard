package com.diffguard.domain.agent.python;

import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.domain.review.model.DiffFileEntry;
import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.domain.review.model.Severity;
import com.diffguard.domain.review.ReviewEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * ReviewEngine 实现：委托给 Python Agent 服务。
 * <p>
 * 用于 PIPELINE 和 MULTI_AGENT 两种模式。
 * Java 收集 diff → 发送到 Python → Python 调用 Java 工具端点 → 返回结果。
 */
public class PythonReviewEngine implements ReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(PythonReviewEngine.class);

    private final PythonAgentClient client;
    private final ReviewConfig config;
    private final String mode;
    private final String toolServerUrl;

    public PythonReviewEngine(String mode, ReviewConfig config, String toolServerUrl) {
        String agentUrl = resolveAgentUrl(config);
        this.client = new PythonAgentClient(agentUrl);
        this.config = config;
        this.mode = mode;
        this.toolServerUrl = toolServerUrl;
    }

    @Override
    public ReviewResult review(List<DiffFileEntry> diffEntries, Path projectDir) throws DiffGuardException {
        long startTime = System.currentTimeMillis();
        ReviewResult result = new ReviewResult();

        try {
            JsonNode response = client.sendReview(mode, config, diffEntries,
                    projectDir.toString(), toolServerUrl);

            // Parse status
            String status = getTextOrDefault(response, "status", "failed");
            if ("failed".equals(status)) {
                String error = getTextOrDefault(response, "error", "Unknown error from Python agent");
                throw new DiffGuardException("Python agent failed: " + error);
            }

            // Parse issues
            JsonNode issuesArray = response.path("issues");
            if (issuesArray.isArray()) {
                for (JsonNode issueNode : issuesArray) {
                    ReviewIssue issue = new ReviewIssue();
                    issue.setSeverity(Severity.fromString(getTextOrDefault(issueNode, "severity", "INFO")));
                    issue.setFile(getTextOrDefault(issueNode, "file", ""));
                    issue.setLine(issueNode.path("line").asInt(0));
                    issue.setType(getTextOrDefault(issueNode, "type", ""));
                    issue.setMessage(getTextOrDefault(issueNode, "message", ""));
                    issue.setSuggestion(getTextOrDefault(issueNode, "suggestion", ""));
                    result.addIssue(issue);
                }
            }

            // Parse metadata
            boolean hasCritical = response.path("has_critical_flag").asBoolean(false);
            result.setHasCriticalFlag(hasCritical);
            result.setTotalTokensUsed(response.path("total_tokens_used").asInt(0));
            result.setTotalFilesReviewed(diffEntries.size());

            long duration = System.currentTimeMillis() - startTime;
            result.setReviewDurationMs(duration);

            log.info("Python agent review completed: {} issues, has_critical={}, duration={}ms",
                    result.getIssues().size(), hasCritical, duration);

        } catch (DiffGuardException e) {
            throw e;
        } catch (Exception e) {
            throw new DiffGuardException("Failed to call Python agent service", e);
        }

        return result;
    }

    @Override
    public void close() {
        // No resources to close
    }

    private static String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asText();
    }

    private static String resolveAgentUrl(ReviewConfig config) {
        String envUrl = System.getenv("DIFFGUARD_AGENT_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            return envUrl.trim();
        }
        ReviewConfig.AgentServiceConfig agentService = config.getAgentService();
        if (agentService != null && agentService.getUrl() != null) {
            return agentService.getUrl();
        }
        return "http://localhost:8000";
    }
}
