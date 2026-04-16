package com.diffguard.config;

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

    public static ReviewConfig load(Path projectDir) {
        // 1. Try project-level config
        File projectConfig = projectDir.resolve(CONFIG_FILENAME).toFile();
        if (projectConfig.exists()) {
            try {
                return MAPPER.readValue(projectConfig, ReviewConfig.class);
            } catch (IOException e) {
                System.err.println("Failed to parse project config: " + e.getMessage());
            }
        }

        // 2. Try user home config
        File homeConfig = Path.of(System.getProperty("user.home"), CONFIG_FILENAME).toFile();
        if (homeConfig.exists()) {
            try {
                return MAPPER.readValue(homeConfig, ReviewConfig.class);
            } catch (IOException e) {
                System.err.println("Failed to parse home config: " + e.getMessage());
            }
        }

        // 3. Fall back to built-in defaults
        try (InputStream is = ConfigLoader.class.getResourceAsStream("/default-config.yml")) {
            if (is != null) {
                return MAPPER.readValue(is, ReviewConfig.class);
            }
        } catch (IOException e) {
            System.err.println("Failed to load default config: " + e.getMessage());
        }

        return new ReviewConfig();
    }
}
