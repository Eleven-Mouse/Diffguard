package com.diffguard.model;

import dev.langchain4j.model.output.structured.Description;

/**
 * 单个审查发现的结构化记录，对应 LLM JSON 输出中的 issues 数组元素。
 */
public record IssueRecord(
        @Description("Issue severity: CRITICAL, WARNING, or INFO")
        String severity,
        @Description("File path relative to project root")
        String file,
        @Description("Line number where the issue was found")
        int line,
        @Description("Issue category, e.g. '安全漏洞', '代码质量', '性能问题'")
        String type,
        @Description("Description of the issue found")
        String message,
        @Description("Suggested fix for the issue")
        String suggestion
) {}
