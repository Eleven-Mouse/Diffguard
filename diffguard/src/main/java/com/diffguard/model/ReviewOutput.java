package com.diffguard.model;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * LLM 审查结果的完整结构化输出，对应 system prompt 定义的 JSON schema。
 */
public record ReviewOutput(
        @Description("Whether any CRITICAL issues were found")
        boolean has_critical,
        @Description("2-4 sentence summary of the changes and potential risks")
        String summary,
        @Description("List of issues found in the code")
        List<IssueRecord> issues,
        @Description("Code quality highlights and good patterns observed")
        List<String> highlights,
        @Description("Suggested additional tests")
        List<String> test_suggestions
) {}
