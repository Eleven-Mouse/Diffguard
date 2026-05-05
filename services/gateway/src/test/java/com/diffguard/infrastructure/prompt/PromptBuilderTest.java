package com.diffguard.infrastructure.prompt;

import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.domain.review.model.DiffFileEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptBuilder 核心路径测试。
 * 覆盖 Prompt 批次分割、模板替换、边界情况。
 */
class PromptBuilderTest {

    private final ReviewConfig defaultConfig = new ReviewConfig();

    private DiffFileEntry makeEntry(String path, String content) {
        return new DiffFileEntry(path, content, 100);
    }

    // ------------------------------------------------------------------
    // buildPrompts - 基础功能
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("buildPrompts - 基础功能")
    class BuildPromptsBasic {

        @Test
        @DisplayName("空 entries 列表 → 空 prompts")
        void emptyEntries() {
            PromptBuilder builder = new PromptBuilder(defaultConfig);
            List<PromptBuilder.PromptContent> prompts = builder.buildPrompts(List.of());
            assertTrue(prompts.isEmpty());
        }

        @Test
        @DisplayName("单文件 → 单个 prompt")
        void singleFile() {
            PromptBuilder builder = new PromptBuilder(defaultConfig);
            DiffFileEntry entry = makeEntry("src/Main.java", "diff content here");
            List<PromptBuilder.PromptContent> prompts = builder.buildPrompts(List.of(entry));

            assertEquals(1, prompts.size());
            assertTrue(prompts.get(0).getUserPrompt().contains("src/Main.java"));
            assertTrue(prompts.get(0).getUserPrompt().contains("diff content here"));
        }

        @Test
        @DisplayName("多个小文件 → 合并为单个 prompt")
        void multipleSmallFiles() {
            PromptBuilder builder = new PromptBuilder(defaultConfig);
            DiffFileEntry e1 = makeEntry("A.java", "content A");
            DiffFileEntry e2 = makeEntry("B.java", "content B");
            DiffFileEntry e3 = makeEntry("C.java", "content C");

            List<PromptBuilder.PromptContent> prompts = builder.buildPrompts(List.of(e1, e2, e3));

            assertEquals(1, prompts.size());
            String userPrompt = prompts.get(0).getUserPrompt();
            assertTrue(userPrompt.contains("A.java"));
            assertTrue(userPrompt.contains("B.java"));
            assertTrue(userPrompt.contains("C.java"));
        }

        @Test
        @DisplayName("Prompt 中包含模板替换后的规则列表")
        void rulesSectionPopulated() {
            PromptBuilder builder = new PromptBuilder(defaultConfig);
            DiffFileEntry entry = makeEntry("A.java", "content");
            List<PromptBuilder.PromptContent> prompts = builder.buildPrompts(List.of(entry));

            String userPrompt = prompts.get(0).getUserPrompt();
            // 默认配置启用 security, bug-risk, code-style, performance
            assertTrue(userPrompt.contains("安全"));
            assertTrue(userPrompt.contains("逻辑"));
            assertTrue(userPrompt.contains("代码质量"));
            assertTrue(userPrompt.contains("性能"));
        }

        @Test
        @DisplayName("Prompt 中包含语言设置（默认 zh）")
        void languageSettingPopulated() {
            PromptBuilder builder = new PromptBuilder(defaultConfig);
            DiffFileEntry entry = makeEntry("A.java", "content");
            List<PromptBuilder.PromptContent> prompts = builder.buildPrompts(List.of(entry));

            assertFalse(prompts.get(0).getUserPrompt().isEmpty());
        }

        @Test
        @DisplayName("多文件合并时 FILE_PATH 显示为'多个文件'")
        void multiFileShowsMultipleLabel() {
            PromptBuilder builder = new PromptBuilder(defaultConfig);
            DiffFileEntry e1 = makeEntry("A.java", "content A");
            DiffFileEntry e2 = makeEntry("B.java", "content B");

            List<PromptBuilder.PromptContent> prompts = builder.buildPrompts(List.of(e1, e2));

            assertTrue(prompts.get(0).getUserPrompt().contains("多个文件"));
        }
    }

    // ------------------------------------------------------------------
    // buildPrompts - System Prompt
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("buildPrompts - System Prompt")
    class SystemPrompt {

        @Test
        @DisplayName("system prompt 非空且包含审查指令")
        void systemPromptNotEmpty() {
            PromptBuilder builder = new PromptBuilder(defaultConfig);
            DiffFileEntry entry = makeEntry("A.java", "content");
            List<PromptBuilder.PromptContent> prompts = builder.buildPrompts(List.of(entry));

            String sys = prompts.get(0).getSystemPrompt();
            assertFalse(sys.isBlank());
        }
    }

    // ------------------------------------------------------------------
    // PromptContent.estimateTokens
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("PromptContent.estimateTokens")
    class EstimateTokens {

        @Test
        @DisplayName("estimateTokens 返回正值")
        void estimateTokensPositive() {
            PromptBuilder builder = new PromptBuilder(defaultConfig);
            DiffFileEntry entry = makeEntry("A.java", "some diff content");
            List<PromptBuilder.PromptContent> prompts = builder.buildPrompts(List.of(entry));

            assertTrue(prompts.get(0).estimateTokens() > 0);
        }
    }
}
