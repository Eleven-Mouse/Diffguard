package com.diffguard.prompt;

import com.diffguard.config.ReviewConfig;
import com.diffguard.model.DiffFileEntry;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class PromptBuilder {

    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    /** Max tokens for the combined diff content in a single request */
    private static final int MAX_COMBINED_TOKENS = 12000;

    private final String systemPrompt;
    private final String userPromptTemplate;
    private final ReviewConfig config;

    public PromptBuilder(ReviewConfig config) {
        this.config = config;
        this.systemPrompt = loadTemplate("/prompt-templates/default-system.txt");
        this.userPromptTemplate = loadTemplate("/prompt-templates/default-user.txt");
    }

    /**
     * Build a single prompt for one file.
     */
    public PromptContent buildPrompt(DiffFileEntry diffEntry) {
        String rulesSection = buildRulesSection();
        String language = config.getReview().getLanguage();

        String userPrompt = userPromptTemplate
                .replace("{{LANGUAGE}}", language)
                .replace("{{RULES}}", rulesSection)
                .replace("{{FILE_PATH}}", diffEntry.getFilePath())
                .replace("{{DIFF_CONTENT}}", diffEntry.getContent());

        return new PromptContent(systemPrompt, userPrompt);
    }

    /**
     * Merge all file diffs into as few prompts as possible.
     * Instead of one API call per file, we combine multiple files into one call
     * to avoid rate limits and reduce total latency.
     */
    public List<PromptContent> buildPrompts(List<DiffFileEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }

        List<PromptContent> results = new ArrayList<>();
        StringBuilder combinedDiff = new StringBuilder();
        int currentTokens = 0;

        for (DiffFileEntry entry : entries) {
            String fileSection = "\n\n--- File: " + entry.getFilePath() + " ---\n" + entry.getContent();
            int fileTokens = ENCODING.countTokens(fileSection);

            // If adding this file would exceed limit, flush current batch first
            if (currentTokens + fileTokens > MAX_COMBINED_TOKENS && combinedDiff.length() > 0) {
                results.add(buildCombinedPrompt(combinedDiff.toString()));
                combinedDiff = new StringBuilder();
                currentTokens = 0;
            }

            combinedDiff.append(fileSection);
            currentTokens += fileTokens;
        }

        // Flush remaining
        if (combinedDiff.length() > 0) {
            results.add(buildCombinedPrompt(combinedDiff.toString()));
        }

        return results;
    }

    private PromptContent buildCombinedPrompt(String allDiffs) {
        String rulesSection = buildRulesSection();
        String language = config.getReview().getLanguage();

        String userPrompt = userPromptTemplate
                .replace("{{LANGUAGE}}", language)
                .replace("{{RULES}}", rulesSection)
                .replace("{{FILE_PATH}}", "(multiple files)")
                .replace("{{DIFF_CONTENT}}", allDiffs);

        return new PromptContent(systemPrompt, userPrompt);
    }

    private String buildRulesSection() {
        StringBuilder sb = new StringBuilder();
        List<String> enabledRules = config.getRules().getEnabled();
        for (String rule : enabledRules) {
            sb.append("- ").append(getRuleDescription(rule)).append("\n");
        }
        return sb.toString();
    }

    private String getRuleDescription(String rule) {
        return switch (rule) {
            case "security" -> "安全漏洞检测（SQL注入、XSS、硬编码密钥、不安全的反序列化）";
            case "bug-risk" -> "逻辑错误检测（空指针、并发问题、资源泄漏、越界访问）";
            case "code-style" -> "代码质量（命名规范、重复代码、过长方法、复杂度）";
            case "performance" -> "性能问题（不必要的对象创建、低效循环、内存泄漏风险）";
            default -> rule;
        };
    }

    private String loadTemplate(String resourcePath) {
        try (InputStream is = PromptBuilder.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                return "";
            }
            return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            System.err.println("Failed to load prompt template: " + resourcePath);
            return "";
        }
    }

    /**
     * Represents a complete prompt with system and user parts.
     */
    public static class PromptContent {
        private final String systemPrompt;
        private final String userPrompt;

        public PromptContent(String systemPrompt, String userPrompt) {
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public String getUserPrompt() {
            return userPrompt;
        }

        public int estimateTokens() {
            return ENCODING.countTokens(systemPrompt) + ENCODING.countTokens(userPrompt);
        }
    }
}
