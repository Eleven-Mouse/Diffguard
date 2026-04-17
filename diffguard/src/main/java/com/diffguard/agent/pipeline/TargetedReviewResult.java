package com.diffguard.agent.pipeline;

import com.diffguard.model.IssueRecord;
import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * Stage 2 专项审查的输出结果。
 * 被 SecurityReviewer、LogicReviewer、QualityReviewer 共用。
 */
public record TargetedReviewResult(
        @Description("本次专项审查的总结")
        String summary,
        @Description("发现的问题列表")
        List<IssueRecord> issues
) {}
