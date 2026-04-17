package com.diffguard.agent.pipeline;

import com.diffguard.agent.pipeline.model.DiffSummary;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Stage 1：Diff 变更总结 Agent。
 * 分析代码变更并输出结构化的变更摘要和风险评估。
 */
@SystemMessage(fromResource = "prompt-templates/pipeline/diff-summary-system.txt")
public interface DiffSummaryAgent {

    @UserMessage("分析以下代码变更，提供结构化的变更总结：\n\n{{diff}}")
    Result<DiffSummary> summarize(@V("diff") String diffContent);
}
