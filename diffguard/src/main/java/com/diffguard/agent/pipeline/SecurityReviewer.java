package com.diffguard.agent.pipeline;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Stage 2：安全专项审查 Agent。
 * 仅关注安全问题：SQL 注入、XSS、硬编码密钥、不安全反序列化、认证绕过等。
 */
@SystemMessage(fromResource = "prompt-templates/pipeline/security-system.txt")
public interface SecurityReviewer {

    @UserMessage("审查以下代码变更的安全问题：\n\n变更总结：{{summary}}\n\n代码变更：\n{{diff}}")
    Result<TargetedReviewResult> review(@V("summary") String summary, @V("diff") String diffContent);
}
