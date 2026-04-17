package com.diffguard.agent.pipeline;

import com.diffguard.agent.pipeline.model.AggregatedReview;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Stage 3：聚合审查 Agent。
 * 合并多个专项审查的结果，去重并生成最终审查报告。
 */
@SystemMessage(fromResource = "prompt-templates/pipeline/aggregation-system.txt")
public interface AggregationAgent {

    @UserMessage("请合并以下专项审查结果：\n\n"
            + "变更总结：{{summary}}\n\n"
            + "安全审查结果：{{securityResult}}\n\n"
            + "逻辑审查结果：{{logicResult}}\n\n"
            + "质量审查结果：{{qualityResult}}")
    Result<AggregatedReview> aggregate(
            @V("summary") String summary,
            @V("securityResult") String securityResult,
            @V("logicResult") String logicResult,
            @V("qualityResult") String qualityResult
    );
}
