package com.diffguard.agent.pipeline;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Stage 2：代码质量和性能审查 Agent。
 * 关注代码质量、可维护性、性能隐患、冗余代码等。
 */
@SystemMessage(fromResource = "prompt-templates/pipeline/quality-system.txt")
public interface QualityReviewer {

    @UserMessage("审查以下代码变更的质量和性能问题：\n\n变更总结：{{summary}}\n\n代码变更：\n{{diff}}")
    Result<TargetedReviewResult> review(@V("summary") String summary, @V("diff") String diffContent);
}
