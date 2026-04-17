package com.diffguard.agent;

import com.diffguard.model.ReviewOutput;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 基于 LangChain4j AiServices 的声明式代码审查接口。
 * <p>
 * 通过 {@link SystemMessage} 和 {@link UserMessage} 注解定义 Prompt 模板，
 * LangChain4j 自动将 LLM 的 JSON 输出反序列化为 {@link ReviewOutput} 对象。
 * <p>
 * 此接口为结构化输出路径（Phase 1），当 LLM 返回有效 JSON 时直接映射，
 * 避免手动 JSON 解析。失败时回退到 {@link LlmClient} 中原有的手动解析逻辑。
 * <p>
 * <b>注意</b>：@UserMessage 模板必须与 {@code prompt-templates/default-user.txt} 保持同步，
 * 否则 Phase 1 和 Phase 2 的审查标准会不一致。
 */
@SystemMessage(fromResource = "/prompt-templates/default-system.txt")
public interface StructuredReviewService {

    @UserMessage("""
            请审查以下代码变更。

            审查语言：{{language}}

            启用的审查规则：
            {{rules}}

            变更文件：{{filePath}}

            代码变更内容（diff格式）：
            ```
            {{diffContent}}
            ```

            【重要】请直接输出 JSON 格式的评审结果。不要输出任何自然语言文本，\
            不要解释你的角色或能力，你的回复必须是一个合法的 JSON 对象，\
            以 { 开头，以 } 结尾。""")
    Result<ReviewOutput> review(@V("language") String language,
                                @V("rules") String rules,
                                @V("filePath") String filePath,
                                @V("diffContent") String diffContent);
}
