package com.diffguard.agent.reviewagents;

import com.diffguard.agent.core.*;
import com.diffguard.agent.tools.ToolRegistry;
import com.diffguard.prompt.PromptLoader;
import dev.langchain4j.model.chat.ChatModel;

import java.nio.file.Path;
import java.util.List;

/**
 * 性能审查 Agent。
 * <p>
 * 专注于发现代码中的性能问题：
 * N+1 查询、循环内 IO、资源泄漏、不必要的对象创建、
 * 低效算法、缓存缺失。
 * <p>
 * 使用 LangChain4j Function Calling 调用工具获取代码上下文。
 */
public class PerformanceReviewAgent {

    private static final String SYSTEM_PROMPT = PromptLoader.load(
            "/prompt-templates/reviewagents/performance-system.txt",
            "你是一个性能审查专家 Agent。专注于发现代码变更中的性能问题。"
    );

    private final ReActAgent agent;

    public PerformanceReviewAgent(ChatModel chatModel, List<AgentTool> tools) {
        this.agent = new ReActAgent(chatModel, tools, SYSTEM_PROMPT, 8);
    }

    /**
     * 测试构造方法：注入自定义 ReActAgent。
     */
    PerformanceReviewAgent(ReActAgent agent) {
        this.agent = agent;
    }

    public static PerformanceReviewAgent create(ChatModel chatModel, Path projectDir) {
        ToolRegistry registry = ToolRegistry.createStandardTools(projectDir);
        return new PerformanceReviewAgent(chatModel, registry.getAllTools());
    }

    public AgentResponse review(AgentContext context) {
        return agent.run(context);
    }
}
