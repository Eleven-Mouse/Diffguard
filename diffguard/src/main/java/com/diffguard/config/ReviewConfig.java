package com.diffguard.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewConfig {

    private LlmConfig llm = new LlmConfig();
    private RulesConfig rules = new RulesConfig();
    private IgnoreConfig ignore = new IgnoreConfig();
    private ReviewOptions review = new ReviewOptions();

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmConfig {
        private String provider = "claude";
        private String model = "claude-sonnet-4-6-20250514";
        private int maxTokens = 4096;
        private double temperature = 0.3;
        private int timeoutSeconds = 60;

        @JsonProperty("api_key_env")
        private String apiKeyEnv = "DIFFGUARD_API_KEY";

        @JsonProperty("api_key")
        private String apiKey = null;

        @JsonProperty("base_url")
        private String baseUrl = null;

        // getters and setters
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
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String resolveBaseUrl() {
            if (baseUrl != null && !baseUrl.isBlank()) {
                return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            }
            // Default URLs based on provider
            return switch (provider.toLowerCase()) {
                case "openai" -> "https://api.openai.com/v1";
                default -> "https://api.anthropic.com";
            };
        }

        public String resolveApiKey() {
            // 1. Direct api_key in config file (highest priority)
            if (apiKey != null && !apiKey.isBlank()) {
                return apiKey.trim();
            }
            // 2. Environment variable
            String key = System.getenv(apiKeyEnv);
            if (key != null && !key.isBlank()) {
                return key.trim();
            }
            throw new IllegalStateException(
                "API key not found. Set api_key in config or environment variable: " + apiKeyEnv);
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
}
