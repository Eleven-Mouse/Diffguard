package com.diffguard.agent.reviewagents;

import com.diffguard.agent.core.*;
import com.diffguard.agent.tools.ToolRegistry;
import com.diffguard.prompt.PromptLoader;
import dev.langchain4j.model.chat.ChatModel;

import java.nio.file.Path;
import java.util.List;

/**
 * 安全审查 Agent。
 * <p>
 * 专注于发现代码中的安全漏洞：
 * SQL 注入、命令注入、XSS、不安全的反序列化、
 * 硬编码密钥、路径遍历、SSRF、认证授权缺陷。
 * <p>
 * 使用 LangChain4j Function Calling 调用工具获取代码上下文。
 */
public class SecurityReviewAgent {

    private static final String SYSTEM_PROMPT = PromptLoader.load(
            "/prompt-templates/reviewagents/security-system.txt",
            "你是一个安全审查专家 Agent。专注于发现代码变更中的安全漏洞。"
    );

    private final ReActAgent agent;

    public SecurityReviewAgent(ChatModel chatModel, List<AgentTool> tools) {
        this.agent = new ReActAgent(chatModel, tools, SYSTEM_PROMPT, 8);
    }

    /**
     * 测试构造方法：注入自定义 ReActAgent。
     */
    SecurityReviewAgent(ReActAgent agent) {
        this.agent = agent;
    }

    public static SecurityReviewAgent create(ChatModel chatModel, Path projectDir) {
        ToolRegistry registry = ToolRegistry.createStandardTools(projectDir);
        return new SecurityReviewAgent(chatModel, registry.getAllTools());
    }

    public AgentResponse review(AgentContext context) {
        return agent.run(context);
    }
}
