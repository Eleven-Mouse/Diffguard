package com.diffguard.agent.pipeline;

import com.diffguard.model.IssueRecord;
import com.diffguard.util.JacksonMapper;
import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * Stage 2 专项审查的输出结果。
 * 被 SecurityReviewer、LogicReviewer、QualityReviewer 共用。
 * <p>
 * 重写 {@link #toString()} 以输出 JSON，确保 Stage 3 聚合阶段能解析结构化数据。
 */
public record TargetedReviewResult(
        @Description("本次专项审查的总结")
        String summary,
        @Description("发现的问题列表")
        List<IssueRecord> issues
) {
    @Override
    public String toString() {
        try {
            return JacksonMapper.MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return "{\"summary\":\"" + (summary != null ? summary : "") + "\",\"issues\":[]}";
        }
    }
}
