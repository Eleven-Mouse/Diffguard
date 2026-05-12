package com.diffguard.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewConfig {

    private LlmConfig llm = new LlmConfig();
    private IgnoreConfig ignore = new IgnoreConfig();
    private ReviewOptions review = new ReviewOptions();
    private WebhookConfig webhook = null;
    private EmbeddingConfig embedding = new EmbeddingConfig();
    private AgentServiceConfig agentService = null;

    public LlmConfig getLlm() { return llm; }
    public void setLlm(LlmConfig llm) { this.llm = llm; }
    public IgnoreConfig getIgnore() { return ignore; }
    public void setIgnore(IgnoreConfig ignore) { this.ignore = ignore; }
    public ReviewOptions getReview() { return review; }
    public void setReview(ReviewOptions review) { this.review = review; }
    public WebhookConfig getWebhook() { return webhook; }
    public void setWebhook(WebhookConfig webhook) { this.webhook = webhook; }
    public EmbeddingConfig getEmbedding() { return embedding; }
    public AgentServiceConfig getAgentService() { return agentService; }
    public void setAgentService(AgentServiceConfig agentService) { this.agentService = agentService; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmConfig {
        private String provider = "claude";
        private String model = "claude-sonnet-4-20250514";

        @JsonProperty("max_tokens")
        private int maxTokens = 4096;

        private double temperature = 0.3;

        @JsonProperty("timeout_seconds")
        private int timeoutSeconds = 300;

        @JsonProperty("api_key_env")
        private String apiKeyEnv = "DIFFGUARD_API_KEY";

        @JsonProperty("base_url")
        private String baseUrl = null;

        @JsonProperty("base_url_env")
        private String baseUrlEnv = "DIFFGUARD_API_BASE_URL";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public String getApiKeyEnv() { return apiKeyEnv; }
        public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getBaseUrlEnv() { return baseUrlEnv; }
        public void setBaseUrlEnv(String baseUrlEnv) { this.baseUrlEnv = baseUrlEnv; }

        public String resolveBaseUrl() {
            if (baseUrlEnv != null && !baseUrlEnv.isBlank()) {
                String envVal = System.getenv(baseUrlEnv);
                if (envVal != null && !envVal.isBlank()) return envVal.trim().replaceAll("/+$", "");
            }
            if (baseUrl != null && !baseUrl.isBlank()) {
                return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            }
            return switch (provider.toLowerCase()) {
                case "openai" -> "https://api.openai.com/v1";
                default -> "https://api.anthropic.com";
            };
        }

        public String resolveApiKey() {
            String envKey = System.getenv(apiKeyEnv);
            if (envKey != null && !envKey.isBlank()) return envKey.trim();
            throw new IllegalStateException("API key not found in env var: " + apiKeyEnv);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IgnoreConfig {
        private List<String> files = List.of("**/*.generated.java", "**/target/**");

        public List<String> getFiles() { return files; }
        public void setFiles(List<String> files) { this.files = files; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReviewOptions {
        @JsonProperty("max_diff_files")
        private int maxDiffFiles = 20;

        @JsonProperty("max_tokens_per_file")
        private int maxTokensPerFile = 4000;

        private String language = "zh";

        public int getMaxDiffFiles() { return maxDiffFiles; }
        public int getMaxTokensPerFile() { return maxTokensPerFile; }
        public String getLanguage() { return language; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WebhookConfig {
        private int port = 8080;
        private String secret = null;

        @JsonProperty("secret_env")
        private String secretEnv = "DIFFGUARD_WEBHOOK_SECRET";

        @JsonProperty("github_token_env")
        private String githubTokenEnv = "DIFFGUARD_GITHUB_TOKEN";

        private List<RepoMapping> repos = List.of();

        public int getPort() { return port; }
        public String getGithubTokenEnv() { return githubTokenEnv; }
        public void setRepos(List<RepoMapping> repos) { this.repos = repos; }

        public String resolveSecret() {
            if (secret != null && !secret.isBlank()) return secret.trim();
            String env = System.getenv(secretEnv);
            return (env != null && !env.isBlank()) ? env.trim() : null;
        }

        public Path resolveLocalPath(String repoFullName) {
            if (repos == null) return null;
            return repos.stream()
                .filter(r -> r.getFullName() != null && r.getFullName().equals(repoFullName))
                .map(r -> Path.of(r.getLocalPath()))
                .findFirst()
                .orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RepoMapping {
        @JsonProperty("full_name")
        private String fullName;

        @JsonProperty("local_path")
        private String localPath;

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getLocalPath() { return localPath; }
        public void setLocalPath(String localPath) { this.localPath = localPath; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbeddingConfig {
        private String provider = "tfidf";
        private String model = "text-embedding-3-small";
        private Integer dimensions = 1536;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Integer getDimensions() { return dimensions; }

        public boolean isOpenAi() { return "openai".equalsIgnoreCase(provider); }

        public String resolveApiKey(String llmApiKeyEnv) {
            String key = System.getenv(llmApiKeyEnv != null ? llmApiKeyEnv : "DIFFGUARD_API_KEY");
            return (key != null && !key.isBlank()) ? key.trim() : null;
        }

        public String resolveBaseUrl(String llmBaseUrl) {
            if (llmBaseUrl != null && !llmBaseUrl.isBlank()) {
                return llmBaseUrl.endsWith("/") ? llmBaseUrl.substring(0, llmBaseUrl.length() - 1) : llmBaseUrl;
            }
            return "https://api.openai.com/v1";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentServiceConfig {
        private String url = "http://localhost:8000";

        @JsonProperty("timeout_seconds")
        private int timeoutSeconds = 300;

        @JsonProperty("tool_server_port")
        private int toolServerPort = 9090;

        @JsonProperty("tool_server_url")
        private String toolServerUrl = null;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getToolServerPort() { return toolServerPort; }
        public void setToolServerPort(int toolServerPort) { this.toolServerPort = toolServerPort; }
        public String getToolServerUrl() { return toolServerUrl; }
        public void setToolServerUrl(String toolServerUrl) { this.toolServerUrl = toolServerUrl; }

        /**
         * Resolve the tool server URL that the Python agent can reach.
         * Priority: explicit tool_server_url config > DIFFGUARD_TOOL_SERVER_URL env > derived from agent URL.
         */
        public String resolveToolServerUrl() {
            // 1. Explicit config
            if (toolServerUrl != null && !toolServerUrl.isBlank()) {
                return toolServerUrl;
            }
            // 2. Environment variable override
            String envUrl = System.getenv("DIFFGUARD_TOOL_SERVER_URL");
            if (envUrl != null && !envUrl.isBlank()) {
                return envUrl.trim();
            }
            // 3. Derive from agent URL: replace agent port with tool server port
            //    e.g. http://diffguard-agent:8000 → http://diffguard-gateway:9090
            //    This works when both services share the same hostname base, but in
            //    Docker they have different hostnames, so we try to derive from the
            //    agent URL's host by replacing known patterns.
            String host = url.replaceAll("^https?://", "").replaceAll(":\\d+.*$", "");
            return "http://" + host + ":" + toolServerPort;
        }
    }
}
