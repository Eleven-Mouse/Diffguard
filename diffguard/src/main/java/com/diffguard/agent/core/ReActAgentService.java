package com.diffguard.agent.core;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * ReAct Agent 的 LangChain4j AiServices 接口。
 * <p>
 * 使用 Function Calling 代替正则解析：
 * LLM 通过原生 Function Calling 调用工具，
 * 通过结构化输出返回审查结果。
 * <p>
 * systemPrompt 和 diff 作为模板变量注入，
 * 以支持不同类型的审查 Agent（安全、性能、架构）。
 */
@SystemMessage("{{systemPrompt}}")
public interface ReActAgentService {

    @UserMessage("请审查以下代码变更。你可以使用可用工具获取更多上下文信息，"
            + "完成审查后直接给出结果。\n\n代码变更：\n{{diff}}")
    Result<ReActReviewOutput> review(
            @V("systemPrompt") String systemPrompt,
            @V("diff") String diffContent);
}
