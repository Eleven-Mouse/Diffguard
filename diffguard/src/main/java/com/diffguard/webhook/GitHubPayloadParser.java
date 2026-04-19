package com.diffguard.webhook;

import com.diffguard.exception.WebhookException;
import com.diffguard.util.JacksonMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 解析 GitHub Webhook pull_request 事件载荷。
 * 使用 JsonNode.path() 安全访问嵌套字段，避免 NPE。
 */
public class GitHubPayloadParser {

    private static final ObjectMapper MAPPER = JacksonMapper.MAPPER;

    public static ParsedPullRequest parse(String payload) throws WebhookException {
        try {
            JsonNode root = MAPPER.readTree(payload);

            String action = defaultIfMissing(root.path("action"), "");
            int prNumber = root.path("number").asInt(0);

            JsonNode pr = root.path("pull_request");
            String repoFullName = defaultIfMissing(pr.path("head").path("repo").path("full_name"), "");
            if (repoFullName.isEmpty()) {
                repoFullName = defaultIfMissing(root.path("repository").path("full_name"), "");
            }

            String baseRef = defaultIfMissing(pr.path("base").path("ref"), "");
            String headRef = defaultIfMissing(pr.path("head").path("ref"), "");
            String headSha = defaultIfMissing(pr.path("head").path("sha"), "");

            return new ParsedPullRequest(action, repoFullName, prNumber, baseRef, headRef, headSha);
        } catch (Exception e) {
            throw new WebhookException("解析 GitHub Webhook 载荷失败", e);
        }
    }

    /**
     * 解析后的 Pull Request 信息。
     */
    public static class ParsedPullRequest {
        private final String action;
        private final String repoFullName;
        private final int prNumber;
        private final String baseRef;
        private final String headRef;
        private final String headSha;

        public ParsedPullRequest(String action, String repoFullName, int prNumber,
                                 String baseRef, String headRef, String headSha) {
            this.action = action;
            this.repoFullName = repoFullName;
            this.prNumber = prNumber;
            this.baseRef = baseRef;
            this.headRef = headRef;
            this.headSha = headSha;
        }

        public boolean isRelevantAction() {
            return "opened".equals(action)
                    || "synchronize".equals(action)
                    || "reopened".equals(action);
        }

        public String getAction() { return action; }
        public String getRepoFullName() { return repoFullName; }
        public int getPrNumber() { return prNumber; }
        public String getBaseRef() { return baseRef; }
        public String getHeadRef() { return headRef; }
        public String getHeadSha() { return headSha; }
    }

    private static String defaultIfMissing(JsonNode node, String defaultValue) {
        return node.isMissingNode() || node.isNull() ? defaultValue : node.asText();
    }
}


