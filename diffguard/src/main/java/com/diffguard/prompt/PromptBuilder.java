package com.diffguard.prompt;

import com.diffguard.config.ReviewConfig;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.util.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    /** 单次请求中合并差异内容的最大 Token 数 */
    private static final int MAX_COMBINED_TOKENS = 6000;

    /** 项目级自定义模板目录 */
    private static final String CUSTOM_PROMPT_DIR = ".diffguard/prompts";

    private static final String SYSTEM_PROMPT_FILE = "system.txt";
    private static final String USER_PROMPT_FILE = "user.txt";

    private final String systemPrompt;
    private final String userPromptTemplate;
    private final ReviewConfig config;

    public PromptBuilder(ReviewConfig config) {
        this(config, Path.of("").toAbsolutePath());
    }

    /**
     * 支持指定项目根目录，用于加载项目级自定义模板。
     */
    public PromptBuilder(ReviewConfig config, Path projectDir) {
        this.config = config;

        // 模板加载优先级：项目自定义 → 内置默认
        String system = loadCustomOrBuiltin(projectDir, SYSTEM_PROMPT_FILE, "/prompt-templates/default-system.txt");
        String user = loadCustomOrBuiltin(projectDir, USER_PROMPT_FILE, "/prompt-templates/default-user.txt");

        if (system.isEmpty()) {
            log.warn("系统提示词模板为空，将使用默认指令");
            system = "你是一名专业的代码审查专家，请审查以下代码变更并给出评审意见。";
        }
        if (user.isEmpty()) {
            log.warn("用户提示词模板为空，将使用默认模板");
            user = "请审查以下代码变更：\n\n```\n{{DIFF_CONTENT}}\n```";
        }

        this.systemPrompt = system;
        this.userPromptTemplate = user;
    }

    /**
     * 将所有文件差异合并为尽可能少的提示词。
     * 不是每个文件一次 API 调用，而是将多个文件合并到一次调用中，
     * 以避免频率限制并减少总延迟。
     */
    public List<PromptContent> buildPrompts(List<DiffFileEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }

        List<PromptContent> results = new ArrayList<>();
        StringBuilder combinedDiff = new StringBuilder();
        int currentTokens = 0;

        for (DiffFileEntry entry : entries) {
            String fileSection = "\n\n--- 文件：" + entry.getFilePath() + " ---\n" + entry.getContent();
            int fileTokens = TokenEstimator.estimate(fileSection, config.getLlm().getProvider());

            // 如果添加此文件会超过限制，先刷新当前批次
            if (currentTokens + fileTokens > MAX_COMBINED_TOKENS && combinedDiff.length() > 0) {
                results.add(buildCombinedPrompt(combinedDiff.toString()));
                combinedDiff = new StringBuilder();
                currentTokens = 0;
            }

            combinedDiff.append(fileSection);
            currentTokens += fileTokens;
        }

        // 刷新剩余内容
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
                .replace("{{FILE_PATH}}", "（多个文件）")
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

    /**
     * 模板加载：优先从项目自定义目录加载，不存在则回退到 classpath 内置模板。
     */
    private String loadCustomOrBuiltin(Path projectDir, String fileName, String builtinPath) {
        // 1. 尝试项目级自定义模板
        Path customFile = projectDir.resolve(CUSTOM_PROMPT_DIR).resolve(fileName);
        if (Files.isRegularFile(customFile)) {
            try {
                String content = Files.readString(customFile, StandardCharsets.UTF_8);
                if (!content.isBlank()) {
                    log.info("使用自定义提示词模板：{}", customFile);
                    return content;
                }
            } catch (IOException e) {
                log.warn("读取自定义模板失败：{}，回退到内置模板", customFile);
            }
        }

        // 2. 回退到 classpath 内置模板
        return loadClasspathResource(builtinPath);
    }

    private String loadClasspathResource(String resourcePath) {
        try (InputStream is = PromptBuilder.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("内置模板不存在：{}", resourcePath);
                return "";
            }
            return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("加载内置模板失败：{}", resourcePath, e);
            return "";
        }
    }

    /**
     * 表示一个完整的提示词，包含系统部分和用户部分。
     */
    public static class PromptContent {
        private final String systemPrompt;
        private final String userPrompt;

        public PromptContent(String systemPrompt, String userPrompt) {
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
        }

        public String getSystemPrompt() { return systemPrompt; }
        public String getUserPrompt() { return userPrompt; }

        public int estimateTokens() {
            return TokenEstimator.count(systemPrompt) + TokenEstimator.count(userPrompt);
        }
    }
}
