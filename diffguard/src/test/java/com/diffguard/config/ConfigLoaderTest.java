package com.diffguard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigLoader 三层配置加载测试。
 * 覆盖：项目级 → 用户主目录 → 内置默认值 → loadFromFile → 错误处理。
 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // loadDefaults
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("loadDefaults - 内置默认配置")
    class LoadDefaults {

        @Test
        @DisplayName("返回非空配置，包含 application.yml 中的设置")
        void returnsNonNullConfig() {
            ReviewConfig config = ConfigLoader.loadDefaults();

            assertNotNull(config);
            assertNotNull(config.getLlm());
            // application.yml 中配置的是 openai provider
            assertEquals("openai", config.getLlm().getProvider());
        }

        @Test
        @DisplayName("默认规则包含 security, bug-risk, code-style, performance")
        void defaultRules() {
            ReviewConfig config = ConfigLoader.loadDefaults();

            assertTrue(config.getRules().getEnabled().contains("security"));
            assertTrue(config.getRules().getEnabled().contains("bug-risk"));
            assertTrue(config.getRules().getEnabled().contains("code-style"));
            assertTrue(config.getRules().getEnabled().contains("performance"));
        }
    }

    // ------------------------------------------------------------------
    // loadFromFile
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("loadFromFile - 指定路径加载")
    class LoadFromFile {

        @Test
        @DisplayName("从有效 YAML 文件加载配置")
        void loadsValidYaml() throws Exception {
            Path configFile = tempDir.resolve("custom-config.yml");
            Files.writeString(configFile, """
                llm:
                  provider: openai
                  model: gpt-4
                  max_tokens: 8192
                  temperature: 0.5
                rules:
                  enabled:
                    - security
                  severity_threshold: warning
                """);

            ReviewConfig config = ConfigLoader.loadFromFile(configFile);

            assertEquals("openai", config.getLlm().getProvider());
            assertEquals("gpt-4", config.getLlm().getModel());
            assertEquals(8192, config.getLlm().getMaxTokens());
            assertEquals(0.5, config.getLlm().getTemperature(), 0.001);
            assertEquals(1, config.getRules().getEnabled().size());
            assertEquals("security", config.getRules().getEnabled().get(0));
        }

        @Test
        @DisplayName("文件不存在时抛出 ConfigException")
        void nonExistentFileThrows() {
            Path nonExistent = tempDir.resolve("no-such-file.yml");

            assertThrows(com.diffguard.exception.ConfigException.class,
                    () -> ConfigLoader.loadFromFile(nonExistent));
        }

        @Test
        @DisplayName("无效 YAML 内容抛出 ConfigException")
        void invalidYamlThrows() throws Exception {
            Path configFile = tempDir.resolve("bad.yml");
            Files.writeString(configFile, "  invalid: yaml: content: [");

            assertThrows(com.diffguard.exception.ConfigException.class,
                    () -> ConfigLoader.loadFromFile(configFile));
        }
    }

    // ------------------------------------------------------------------
    // load - 三层加载优先级
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("load - 三层加载优先级")
    class LoadPriority {

        @Test
        @DisplayName("无项目级和用户级配置时，回退到内置默认值")
        void fallsBackToDefaults() throws Exception {
            // tempDir 中无 .review-config.yml
            ReviewConfig config = ConfigLoader.load(tempDir);

            assertNotNull(config);
            // application.yml 中默认 provider 是 openai
            assertEquals("openai", config.getLlm().getProvider());
        }

        @Test
        @DisplayName("项目级配置优先于内置默认值")
        void projectConfigOverridesDefaults() throws Exception {
            Path projectConfig = tempDir.resolve(".review-config.yml");
            Files.writeString(projectConfig, """
                llm:
                  provider: openai
                  model: gpt-3.5-turbo
                  max_tokens: 2048
                """);

            ReviewConfig config = ConfigLoader.load(tempDir);

            assertEquals("openai", config.getLlm().getProvider());
            assertEquals("gpt-3.5-turbo", config.getLlm().getModel());
            assertEquals(2048, config.getLlm().getMaxTokens());
        }

        @Test
        @DisplayName("项目级配置中仅覆盖指定字段，未指定的保持默认")
        void projectConfigPartialOverride() throws Exception {
            Path projectConfig = tempDir.resolve(".review-config.yml");
            Files.writeString(projectConfig, """
                llm:
                  provider: openai
                rules:
                  enabled:
                    - security
                """);

            ReviewConfig config = ConfigLoader.load(tempDir);

            // 覆盖的字段
            assertEquals("openai", config.getLlm().getProvider());
            assertEquals(1, config.getRules().getEnabled().size());
            // 未覆盖的字段保持默认
            assertNotNull(config.getIgnore());
            assertNotNull(config.getReview());
        }
    }
}
