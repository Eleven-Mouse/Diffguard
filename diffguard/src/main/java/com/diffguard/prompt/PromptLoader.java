package com.diffguard.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Prompt 模板加载工具。
 */
public final class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    private PromptLoader() {}

    /**
     * 从 classpath 加载 Prompt 模板。
     *
     * @param resourcePath classpath 资源路径，如 "/prompt-templates/reviewagents/security-system.txt"
     * @param fallback     加载失败时的回退内容
     * @return 模板内容
     */
    public static String load(String resourcePath, String fallback) {
        try (InputStream is = PromptLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("Prompt 模板未找到: {}，使用回退内容", resourcePath);
                return fallback;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            log.warn("加载 Prompt 模板失败: {}，使用回退内容", resourcePath, e);
            return fallback;
        }
    }
}
