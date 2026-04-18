package com.diffguard.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * JSON 格式化重试 Prompt 模板懒加载器。
 * <p>
 * 从 classpath 加载 JSON 重试所需的 system/user prompt 模板，
 * 使用 volatile + DCL 保证线程安全的懒初始化。
 */
public final class JsonRetryPromptLoader {

    private static final Logger log = LoggerFactory.getLogger(JsonRetryPromptLoader.class);

    private static volatile String jsonRetrySystemPrompt;
    private static volatile String jsonRetryUserTemplate;

    private JsonRetryPromptLoader() {}

    public static String loadJsonRetrySystemPrompt() {
        if (jsonRetrySystemPrompt == null) {
            synchronized (JsonRetryPromptLoader.class) {
                if (jsonRetrySystemPrompt == null) {
                    jsonRetrySystemPrompt = loadTemplate("/prompt-templates/json-retry-system.txt",
                            "你是一个格式转换助手。你的唯一任务是将用户给出的代码审查内容转换为严格的 JSON 格式。你必须且仅输出一个合法的 JSON 对象，不得包含任何其他文本。");
                }
            }
        }
        return jsonRetrySystemPrompt;
    }

    public static String loadJsonRetryUserTemplate() {
        if (jsonRetryUserTemplate == null) {
            synchronized (JsonRetryPromptLoader.class) {
                if (jsonRetryUserTemplate == null) {
                    jsonRetryUserTemplate = loadTemplate("/prompt-templates/json-retry-user.txt", null);
                    if (jsonRetryUserTemplate == null) {
                        jsonRetryUserTemplate = "以下是一次代码审查的原始回复内容，请将其转换为以下 JSON 格式：\n\n"
                                + "```json\n"
                                + "{\"has_critical\": boolean, \"summary\": \"总结\", "
                                + "\"issues\": [{\"severity\": \"CRITICAL|WARNING|INFO\", \"file\": \"路径\", "
                                + "\"line\": 行号, \"type\": \"类型\", \"message\": \"描述\", \"suggestion\": \"建议\"}], "
                                + "\"highlights\": [\"亮点\"], \"test_suggestions\": [\"测试建议\"]}\n```\n\n"
                                + "原始回复内容：\n%s";
                    }
                }
            }
        }
        return jsonRetryUserTemplate;
    }

    static String loadTemplate(String resourcePath, String fallback) {
        try (InputStream is = JsonRetryPromptLoader.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines().collect(java.util.stream.Collectors.joining("\n"));
            }
        } catch (Exception e) {
            log.warn("加载 prompt 模板失败：{}", resourcePath);
        }
        return fallback;
    }

    /** 重置缓存，仅用于测试。 */
    static void reset() {
        jsonRetrySystemPrompt = null;
        jsonRetryUserTemplate = null;
    }
}
