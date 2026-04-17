package com.diffguard.agent.pipeline.model;

import com.diffguard.model.IssueRecord;
import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * Stage 3 输出：聚合后的最终审查结果。
 */
public record AggregatedReview(
        @Description("是否存在 CRITICAL 级别问题")
        boolean has_critical,
        @Description("综合审查总结")
        String summary,
        @Description("去重合并后的所有问题")
        List<IssueRecord> issues,
        @Description("代码亮点")
        List<String> highlights,
        @Description("测试建议")
        List<String> test_suggestions
) {}
