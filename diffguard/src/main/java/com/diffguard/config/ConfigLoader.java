package com.diffguard.config;

import com.diffguard.exception.ConfigException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public class ConfigLoader {

    private static final String CONFIG_FILENAME = ".review-config.yml";
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 三层配置加载：项目级 → 用户主目录 → 内置默认值。
     * 解析失败时抛出 ConfigException 而非静默降级。
     */
    public static ReviewConfig load(Path projectDir) throws ConfigException {
        // 1. 尝试项目级配置
        File projectConfig = projectDir.resolve(CONFIG_FILENAME).toFile();
        if (projectConfig.exists()) {
            try {
                return MAPPER.readValue(projectConfig, ReviewConfig.class);
            } catch (IOException e) {
                throw new ConfigException("解析项目配置失败：" + e.getMessage(), e);
            }
        }

        // 2. 尝试用户主目录配置
        File homeConfig = Path.of(System.getProperty("user.home"), CONFIG_FILENAME).toFile();
        if (homeConfig.exists()) {
            try {
                return MAPPER.readValue(homeConfig, ReviewConfig.class);
            } catch (IOException e) {
                throw new ConfigException("解析用户主目录配置失败：" + e.getMessage(), e);
            }
        }

        // 3. 回退到内置默认配置
        return loadDefaults();
    }

    /**
     * 从指定路径直接加载配置文件（支持 --config 参数）。
     */
    public static ReviewConfig loadFromFile(Path configPath) throws ConfigException {
        File configFile = configPath.toFile();
        if (!configFile.exists()) {
            throw new ConfigException("配置文件不存在：" + configPath);
        }
        try {
            return MAPPER.readValue(configFile, ReviewConfig.class);
        } catch (IOException e) {
            throw new ConfigException("解析配置文件失败：" + e.getMessage(), e);
        }
    }

    public static ReviewConfig loadDefaults() {
        try (InputStream is = ConfigLoader.class.getResourceAsStream("/application.yml")) {
            if (is != null) {
                return MAPPER.readValue(is, ReviewConfig.class);
            }
        } catch (IOException e) {
            // 内置默认配置不应失败，回退到空配置
        }
        return new ReviewConfig();
    }
}
