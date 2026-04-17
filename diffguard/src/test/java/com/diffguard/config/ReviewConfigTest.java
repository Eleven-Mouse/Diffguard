package com.diffguard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReviewConfig 核心配置逻辑测试。
 * 覆盖 API Key 解析、Base URL 解析、默认值、边界情况。
 */
class ReviewConfigTest {

    // ------------------------------------------------------------------
    // LlmConfig.resolveApiKey
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("LlmConfig.resolveApiKey")
    class ResolveApiKey {

        @Test
        @DisplayName("环境变量未设置时抛出异常")
        void envNotSetThrows() {
            ReviewConfig.LlmConfig config = new ReviewConfig.LlmConfig();

            // 环境变量 DIFFGUARD_API_KEY 通常未设置，应抛异常
            assertThrows(IllegalStateException.class, config::resolveApiKey);
        }

        @Test
        @DisplayName("自定义环境变量名生效")
        void customEnvVarName() {
            ReviewConfig.LlmConfig config = new ReviewConfig.LlmConfig();
            config.setApiKeyEnv("NONEXISTENT_API_KEY_FOR_TEST");

            assertThrows(IllegalStateException.class, config::resolveApiKey);
            assertTrue(config.getApiKeyEnv().equals("NONEXISTENT_API_KEY_FOR_TEST"));
        }
    }

    // ------------------------------------------------------------------
    // LlmConfig.resolveBaseUrl
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("LlmConfig.resolveBaseUrl")
    class ResolveBaseUrl {

        @Test
        @DisplayName("自定义 base_url 去除末尾斜杠")
        void customBaseUrlTrailingSlashRemoved() {
            ReviewConfig.LlmConfig config = new ReviewConfig.LlmConfig();
            config.setBaseUrl("https://api.example.com/");

            assertEquals("https://api.example.com", config.resolveBaseUrl());
        }

        @Test
        @DisplayName("自定义 base_url 无末尾斜杠保持不变")
        void customBaseUrlNoTrailingSlash() {
            ReviewConfig.LlmConfig config = new ReviewConfig.LlmConfig();
            config.setBaseUrl("https://api.example.com");

            assertEquals("https://api.example.com", config.resolveBaseUrl());
        }

        @Test
        @DisplayName("openai provider 默认 URL")
        void openaiDefaultUrl() {
            ReviewConfig.LlmConfig config = new ReviewConfig.LlmConfig();
            config.setProvider("openai");

            assertEquals("https://api.openai.com/v1", config.resolveBaseUrl());
        }

        @Test
        @DisplayName("claude provider 默认 URL")
        void claudeDefaultUrl() {
            ReviewConfig.LlmConfig config = new ReviewConfig.LlmConfig();
            config.setProvider("claude");

            assertEquals("https://api.anthropic.com", config.resolveBaseUrl());
        }

        @Test
        @DisplayName("自定义 base_url 优先于 provider 默认 URL")
        void customUrlOverridesDefault() {
            ReviewConfig.LlmConfig config = new ReviewConfig.LlmConfig();
            config.setProvider("openai");
            config.setBaseUrl("https://proxy.example.com");

            assertEquals("https://proxy.example.com", config.resolveBaseUrl());
        }
    }

    // ------------------------------------------------------------------
    // 默认值验证
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("默认值验证")
    class Defaults {

        @Test
        @DisplayName("LlmConfig 默认值正确")
        void llmDefaults() {
            ReviewConfig.LlmConfig config = new ReviewConfig.LlmConfig();

            assertEquals("claude", config.getProvider());
            assertEquals(4096, config.getMaxTokens());
            assertEquals(0.3, config.getTemperature(), 0.001);
            assertEquals(300, config.getTimeoutSeconds());
            assertEquals("DIFFGUARD_API_KEY", config.getApiKeyEnv());
        }

        @Test
        @DisplayName("RulesConfig 默认启用 4 条规则")
        void rulesDefaults() {
            ReviewConfig.RulesConfig rules = new ReviewConfig.RulesConfig();

            assertEquals(4, rules.getEnabled().size());
            assertTrue(rules.getEnabled().contains("security"));
            assertTrue(rules.getEnabled().contains("bug-risk"));
            assertTrue(rules.getEnabled().contains("code-style"));
            assertTrue(rules.getEnabled().contains("performance"));
        }

        @Test
        @DisplayName("ReviewOptions 默认值正确")
        void reviewOptionsDefaults() {
            ReviewConfig.ReviewOptions opts = new ReviewConfig.ReviewOptions();

            assertEquals(20, opts.getMaxDiffFiles());
            assertEquals(4000, opts.getMaxTokensPerFile());
            assertEquals("zh", opts.getLanguage());
        }

        @Test
        @DisplayName("IgnoreConfig 默认忽略 generated 文件和 target 目录")
        void ignoreDefaults() {
            ReviewConfig.IgnoreConfig ignore = new ReviewConfig.IgnoreConfig();

            assertTrue(ignore.getFiles().stream().anyMatch(p -> p.contains("generated")));
            assertTrue(ignore.getFiles().stream().anyMatch(p -> p.contains("target")));
        }
    }

    // ------------------------------------------------------------------
    // ReviewConfig 整体
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("ReviewConfig 整体")
    class WholeConfig {

        @Test
        @DisplayName("new ReviewConfig() 所有子配置均已初始化")
        void allSubConfigsInitialized() {
            ReviewConfig config = new ReviewConfig();

            assertNotNull(config.getLlm());
            assertNotNull(config.getRules());
            assertNotNull(config.getIgnore());
            assertNotNull(config.getReview());
        }
    }
}
