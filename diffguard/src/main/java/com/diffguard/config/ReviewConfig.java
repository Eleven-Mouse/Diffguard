package com.diffguard.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewConfig {

    private LlmConfig llm = new LlmConfig();
    private RulesConfig rules = new RulesConfig();
    private IgnoreConfig ignore = new IgnoreConfig();
    private ReviewOptions review = new ReviewOptions();
    private WebhookConfig webhook = null;

    public LlmConfig getLlm() {
        return llm;
    }

    public void setLlm(LlmConfig llm) {
        this.llm = llm;
    }

    public RulesConfig getRules() {
        return rules;
    }

    public void setRules(RulesConfig rules) {
        this.rules = rules;
    }

    public IgnoreConfig getIgnore() {
        return ignore;
    }

    public void setIgnore(IgnoreConfig ignore) {
        this.ignore = ignore;
    }

    public ReviewOptions getReview() {
        return review;
    }

    public void setReview(ReviewOptions review) {
        this.review = review;
    }

    public WebhookConfig getWebhook() {
        return webhook;
    }

    public void setWebhook(WebhookConfig webhook) {
        this.webhook = webhook;
    }

    /**
     * 校验配置参数合法性。
     *
     * @throws IllegalArgumentException 配置不合法时抛出
     */
    public void validate() {
        llm.validate();
        if (review.maxDiffFiles <= 0) {
            throw new IllegalArgumentException("review.max_diff_files 必须大于 0，当前值：" + review.maxDiffFiles);
        }
        if (review.maxTokensPerFile <= 0) {
            throw new IllegalArgumentException("review.max_tokens_per_file 必须大于 0，当前值：" + review.maxTokensPerFile);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmConfig {
        private String provider = "claude";
        private String model = "claude-sonnet-4-6";

        @JsonProperty("max_tokens")
        private int maxTokens = 4096;

        private double temperature = 0.3;

        @JsonProperty("timeout_seconds")
        private int timeoutSeconds = 300;

        @JsonProperty("api_key_env")
        private String apiKeyEnv = "DIFFGUARD_API_KEY";

        @JsonProperty("base_url")
        private String baseUrl = null;

        // getter 和 setter 方法
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

        public String resolveBaseUrl() {
            if (baseUrl != null && !baseUrl.isBlank()) {
                return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            }
            // 根据提供商返回默认URL
            return switch (provider.toLowerCase()) {
                case "openai" -> "https://api.openai.com/v1";
                default -> "https://api.anthropic.com";
            };
        }

        public String resolveApiKey() {
            // 仅通过环境变量获取，不允许明文存储 API Key
            String envKey = System.getenv(apiKeyEnv);
            if (envKey != null && !envKey.isBlank()) {
                return envKey.trim();
            }
            throw new IllegalStateException(
                "未找到API密钥。请通过环境变量设置：" + apiKeyEnv);
        }

        /**
         * 校验 LLM 配置参数合法性。
         *
         * @throws IllegalArgumentException 配置不合法时抛出
         */
        public void validate() {
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("llm.provider 不能为空");
            }
            if (!provider.equalsIgnoreCase("claude") && !provider.equalsIgnoreCase("openai")) {
                throw new IllegalArgumentException("不支持的 llm.provider：" + provider + "（支持 claude/openai）");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("llm.model 不能为空");
            }
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("llm.max_tokens 必须大于 0，当前值：" + maxTokens);
            }
            if (temperature < 0 || temperature > 2) {
                throw new IllegalArgumentException("llm.temperature 必须在 0-2 范围内，当前值：" + temperature);
            }
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("llm.timeout_seconds 必须大于 0，当前值：" + timeoutSeconds);
            }
            if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
                throw new IllegalArgumentException("llm.api_key_env 不能为空");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RulesConfig {
        private List<String> enabled = List.of("security", "bug-risk", "code-style", "performance");

        @JsonProperty("severity_threshold")
        private String severityThreshold = "info";

        public List<String> getEnabled() { return enabled; }
        public void setEnabled(List<String> enabled) { this.enabled = enabled; }
        public String getSeverityThreshold() { return severityThreshold; }
        public void setSeverityThreshold(String severityThreshold) { this.severityThreshold = severityThreshold; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IgnoreConfig {
        private List<String> files = List.of("**/*.generated.java", "**/target/**");
        private List<String> patterns = List.of(".*import statement.*");

        public List<String> getFiles() { return files; }
        public void setFiles(List<String> files) { this.files = files; }
        public List<String> getPatterns() { return patterns; }
        public void setPatterns(List<String> patterns) { this.patterns = patterns; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReviewOptions {
        @JsonProperty("max_diff_files")
        private int maxDiffFiles = 20;

        @JsonProperty("max_tokens_per_file")
        private int maxTokensPerFile = 4000;

        private String language = "zh";

        public int getMaxDiffFiles() { return maxDiffFiles; }
        public void setMaxDiffFiles(int maxDiffFiles) { this.maxDiffFiles = maxDiffFiles; }
        public int getMaxTokensPerFile() { return maxTokensPerFile; }
        public void setMaxTokensPerFile(int maxTokensPerFile) { this.maxTokensPerFile = maxTokensPerFile; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
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
        public void setPort(int port) { this.port = port; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getSecretEnv() { return secretEnv; }
        public void setSecretEnv(String secretEnv) { this.secretEnv = secretEnv; }
        public String getGithubTokenEnv() { return githubTokenEnv; }
        public void setGithubTokenEnv(String githubTokenEnv) { this.githubTokenEnv = githubTokenEnv; }
        public List<RepoMapping> getRepos() { return repos; }
        public void setRepos(List<RepoMapping> repos) { this.repos = repos; }

        public String resolveSecret() {
            if (secret != null && !secret.isBlank()) {
                return secret.trim();
            }
            String env = System.getenv(secretEnv);
            return (env != null && !env.isBlank()) ? env.trim() : null;
        }

        public String resolveGitHubToken() {
            String token = System.getenv(githubTokenEnv);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException(
                    "未找到 GitHub Token。请通过环境变量设置：" + githubTokenEnv);
            }
            return token.trim();
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
}
