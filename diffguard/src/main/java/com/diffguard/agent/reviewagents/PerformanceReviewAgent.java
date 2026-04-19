package com.diffguard.agent.reviewagents;

import com.diffguard.agent.core.*;
import com.diffguard.agent.strategy.ReviewStrategy;
import com.diffguard.agent.tools.SemanticSearchTool;
import com.diffguard.agent.tools.ToolRegistry;
import com.diffguard.config.ReviewConfig;
import com.diffguard.prompt.PromptLoader;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(PerformanceReviewAgent.class);

    private static final String SYSTEM_PROMPT = PromptLoader.load(
            "/prompt-templates/reviewagents/performance-system.txt",
            "你是一个性能审查专家 Agent。专注于发现代码变更中的性能问题。"
    );

    private final ReActAgent agent;

    public PerformanceReviewAgent(ChatModel chatModel, List<AgentTool> tools) {
        this.agent = new ReActAgent(chatModel, tools, SYSTEM_PROMPT, 8);
    }

    /**
     * 带审查策略的构造方法，注入策略中的重点领域和额外规则。
     */
    public PerformanceReviewAgent(ChatModel chatModel, List<AgentTool> tools, ReviewStrategy strategy) {
        this.agent = new ReActAgent(chatModel, tools,
                SecurityReviewAgent.buildPromptWithStrategy(SYSTEM_PROMPT, strategy), 8);
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

    /**
     * 根据配置创建性能审查 Agent，支持配置化的 Embedding 提供者。
     */
    public static PerformanceReviewAgent create(ChatModel chatModel, Path projectDir, ReviewConfig config) {
        ToolRegistry registry = ToolRegistry.createStandardTools(projectDir);
        try {
            registry.register(new SemanticSearchTool(projectDir, config));
        } catch (Exception e) {
            log.debug("Semantic search tool 初始化跳过: {}", e.getMessage());
        }
        return new PerformanceReviewAgent(chatModel, registry.getAllTools());
    }

    /**
     * 带策略的工厂方法。
     */
    public static PerformanceReviewAgent create(ChatModel chatModel, Path projectDir, ReviewStrategy strategy) {
        ToolRegistry registry = ToolRegistry.createStandardTools(projectDir);
        return new PerformanceReviewAgent(chatModel, registry.getAllTools(), strategy);
    }

    /**
     * 带配置和策略的工厂方法。
     */
    public static PerformanceReviewAgent create(ChatModel chatModel, Path projectDir, ReviewConfig config, ReviewStrategy strategy) {
        ToolRegistry registry = ToolRegistry.createStandardTools(projectDir);
        try {
            registry.register(new SemanticSearchTool(projectDir, config));
        } catch (Exception e) {
            log.debug("Semantic search tool 初始化跳过: {}", e.getMessage());
        }
        return new PerformanceReviewAgent(chatModel, registry.getAllTools(), strategy);
    }

    public AgentResponse review(AgentContext context) {
        return agent.run(context);
    }
}
