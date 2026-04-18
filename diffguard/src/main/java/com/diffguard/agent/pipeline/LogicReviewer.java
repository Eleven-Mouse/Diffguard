package com.diffguard.agent.pipeline;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Stage 2：逻辑和 Bug 风险审查 Agent。
 * 关注逻辑错误、空指针、并发问题、资源泄漏、边界条件等。
 */
@SystemMessage(fromResource = "prompt-templates/pipeline/logic-system.txt")
public interface LogicReviewer {

    @UserMessage(fromResource = "/prompt-templates/pipeline/logic-user.txt")
    Result<TargetedReviewResult> review(@V("summary") String summary, @V("diff") String diffContent);
}
