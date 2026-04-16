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

    private final String systemPrompt;
    private final String userPromptTemplate;
    private final ReviewConfig config;

    public PromptBuilder(ReviewConfig config) {
        this.config = config;
        this.systemPrompt = loadTemplate("/prompt-templates/default-system.txt");
        this.userPromptTemplate = loadTemplate("/prompt-templates/default-user.txt");
    }

    /**
     * Build a complete prompt for a single file diff.
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
     * Split a large diff into multiple prompts if it exceeds token limit.
     */
    public List<PromptContent> buildPrompts(List<DiffFileEntry> entries) {
        int maxTokensPerFile = config.getReview().getMaxTokensPerFile();

        return entries.stream()
                .flatMap(entry -> {
                    if (entry.exceedsTokenLimit(maxTokensPerFile)) {
                        return splitLargeDiff(entry).stream();
                    } else {
                        return List.of(buildPrompt(entry)).stream();
                    }
                })
                .toList();
    }

    /**
     * Split a large diff by hunk boundaries (double newlines in diff output).
     */
    private List<PromptContent> splitLargeDiff(DiffFileEntry entry) {
        int maxTokens = config.getReview().getMaxTokensPerFile();
        String content = entry.getContent();

        // Split by hunk separator: "@@ ... @@"
        String[] hunks = content.split("(?=@@)");
        List<PromptContent> results = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String hunk : hunks) {
            String testContent = currentChunk.toString() + hunk;
            if (ENCODING.countTokens(testContent) > maxTokens && currentChunk.length() > 0) {
                DiffFileEntry chunkEntry = new DiffFileEntry(
                        entry.getFilePath(), currentChunk.toString(),
                        ENCODING.countTokens(currentChunk.toString()));
                results.add(buildPrompt(chunkEntry));
                currentChunk = new StringBuilder(hunk);
            } else {
                currentChunk.append(hunk);
            }
        }

        if (currentChunk.length() > 0) {
            DiffFileEntry chunkEntry = new DiffFileEntry(
                    entry.getFilePath(), currentChunk.toString(),
                    ENCODING.countTokens(currentChunk.toString()));
            results.add(buildPrompt(chunkEntry));
        }

        return results;
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
