package com.diffguard.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Prompt 模板统一管理器。
 * <p>
 * 负责加载、缓存和分发所有 Prompt 模板。
 * 支持从 classpath 和文件系统加载，文件系统优先。
 */
public final class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);
    private static final String CLASSPATH_PREFIX = "/prompt-templates/";

    private static volatile PromptManager instance;

    private final Map<String, String> cache = new LinkedHashMap<>();
    private final Path customPromptDir;

    private PromptManager(Path customPromptDir) {
        this.customPromptDir = customPromptDir;
    }

    /**
     * 获取单例实例（无自定义 Prompt 目录）。
     */
    public static PromptManager getInstance() {
        if (instance == null) {
            synchronized (PromptManager.class) {
                if (instance == null) {
                    instance = new PromptManager(null);
                }
            }
        }
        return instance;
    }

    /**
     * 创建带自定义 Prompt 目录的实例。
     *
     * @param customPromptDir 自定义 Prompt 目录路径，如 .diffguard/prompts/
     */
    public static PromptManager create(Path customPromptDir) {
        return new PromptManager(customPromptDir);
    }

    /**
     * 获取 Prompt 模板。
     * <p>
     * 加载优先级：
     * 1. 内存缓存
     * 2. 自定义 Prompt 目录（如 .diffguard/prompts/）
     * 3. Classpath 默认模板（/prompt-templates/）
     *
     * @param name     模板名称，如 "default-system"、"pipeline/security-system"
     * @param fallback 加载失败时的回退内容
     * @return 模板内容
     */
    public String getPrompt(String name, String fallback) {
        return cache.computeIfAbsent(name, n -> loadPrompt(n, fallback));
    }

    /**
     * 清除缓存，强制下次重新加载。
     */
    public void clearCache() {
        cache.clear();
    }

    private String loadPrompt(String name, String fallback) {
        // 优先从自定义目录加载
        if (customPromptDir != null) {
            Path customFile = customPromptDir.resolve(name + ".txt");
            if (Files.isReadable(customFile)) {
                try {
                    String content = Files.readString(customFile, StandardCharsets.UTF_8);
                    log.debug("从自定义目录加载 Prompt: {}", customFile);
                    return content;
                } catch (IOException e) {
                    log.warn("读取自定义 Prompt 失败: {}", customFile, e);
                }
            }
        }

        // 从 classpath 加载
        String resourcePath = CLASSPATH_PREFIX + name + ".txt";
        try (InputStream is = PromptManager.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    log.debug("从 classpath 加载 Prompt: {}", resourcePath);
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            }
        } catch (IOException e) {
            log.warn("加载 classpath Prompt 失败: {}", resourcePath, e);
        }

        log.warn("Prompt 模板未找到: {}，使用回退内容", name);
        return fallback;
    }

    /**
     * 列出所有可用的 classpath 模板名称。
     */
    public Map<String, String> listAvailableTemplates() {
        // classpath 资源无法直接列出文件，返回已缓存的模板
        return Collections.unmodifiableMap(cache);
    }
}
