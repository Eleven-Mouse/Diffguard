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

    @UserMessage(fromResource = "/prompt-templates/pipeline/diff-summary-user.txt")
    Result<DiffSummary> summarize(@V("diff") String diffContent);
}
