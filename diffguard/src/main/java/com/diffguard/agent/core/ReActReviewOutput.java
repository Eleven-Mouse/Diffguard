package com.diffguard.agent.core;

import com.diffguard.model.IssueRecord;
import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * ReAct Agent 结构化输出。
 * <p>
 * LangChain4j AiServices 会自动将 LLM 的 JSON 输出反序列化为此 record。
 * 字段名使用 snake_case 以匹配 JSON 输出格式。
 */
public record ReActReviewOutput(
        @Description("Whether there are CRITICAL level issues that must be fixed")
        Boolean has_critical,
        @Description("Summary of the code review findings")
        String summary,
        @Description("List of issues found during the review")
        List<IssueRecord> issues,
        @Description("Good practices or notable code patterns found")
        List<String> highlights,
        @Description("Suggested tests for the reviewed changes")
        List<String> test_suggestions
) {}
