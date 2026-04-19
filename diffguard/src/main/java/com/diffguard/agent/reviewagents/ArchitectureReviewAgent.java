package com.diffguard.agent.reviewagents;

import com.diffguard.agent.core.*;
import com.diffguard.agent.strategy.ReviewStrategy;
import com.diffguard.agent.tools.GetCallGraphTool;
import com.diffguard.agent.tools.GetRelatedFilesTool;
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
 * 架构审查 Agent。
 * <p>
 * 专注于代码的架构和设计问题：
 * 分层违规、职责混乱、接口不兼容、循环依赖、
 * 过度耦合、缺少抽象。
 * <p>
 * 使用 LangChain4j Function Calling 调用工具获取代码上下文。
 * 额外配备 Code Graph 工具用于分析跨文件依赖关系。
 */
public class ArchitectureReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureReviewAgent.class);

    private static final String SYSTEM_PROMPT = PromptLoader.load(
            "/prompt-templates/reviewagents/architecture-system.txt",
            "你是一个架构审查专家 Agent。专注于发现代码变更中的架构和设计问题。"
    );

    private final ReActAgent agent;

    public ArchitectureReviewAgent(ChatModel chatModel, List<AgentTool> tools) {
        this.agent = new ReActAgent(chatModel, tools, SYSTEM_PROMPT, 8);
    }

    /**
     * 带审查策略的构造方法，注入策略中的重点领域和额外规则。
     */
    public ArchitectureReviewAgent(ChatModel chatModel, List<AgentTool> tools, ReviewStrategy strategy) {
        this.agent = new ReActAgent(chatModel, tools,
                SecurityReviewAgent.buildPromptWithStrategy(SYSTEM_PROMPT, strategy), 8);
    }

    /**
     * 测试构造方法：注入自定义 ReActAgent。
     */
    ArchitectureReviewAgent(ReActAgent agent) {
        this.agent = agent;
    }

    public static ArchitectureReviewAgent create(ChatModel chatModel, Path projectDir) {
        ToolRegistry registry = ToolRegistry.createStandardTools(projectDir);
        try {
            GetCallGraphTool callGraphTool = new GetCallGraphTool(projectDir);
            registry.register(callGraphTool);
            registry.register(new GetRelatedFilesTool(callGraphTool));
        } catch (Exception e) {
            // Code graph may fail on small/empty projects, continue without it
        }
        return new ArchitectureReviewAgent(chatModel, registry.getAllTools());
    }

    /**
     * 根据配置创建架构审查 Agent，支持配置化的 Embedding 提供者。
     */
    public static ArchitectureReviewAgent create(ChatModel chatModel, Path projectDir, ReviewConfig config) {
        ToolRegistry registry = ToolRegistry.createStandardTools(projectDir);
        try {
            GetCallGraphTool callGraphTool = new GetCallGraphTool(projectDir);
            registry.register(callGraphTool);
            registry.register(new GetRelatedFilesTool(callGraphTool));
        } catch (Exception e) {
            // Code graph may fail on small/empty projects, continue without it
        }
        try {
            registry.register(new SemanticSearchTool(projectDir, config));
        } catch (Exception e) {
            log.debug("Semantic search tool 初始化跳过: {}", e.getMessage());
        }
        return new ArchitectureReviewAgent(chatModel, registry.getAllTools());
    }

    /**
     * 带策略的工厂方法。
     */
    public static ArchitectureReviewAgent create(ChatModel chatModel, Path projectDir, ReviewStrategy strategy) {
        ToolRegistry registry = ToolRegistry.createStandardTools(projectDir);
        try {
            GetCallGraphTool callGraphTool = new GetCallGraphTool(projectDir);
            registry.register(callGraphTool);
            registry.register(new GetRelatedFilesTool(callGraphTool));
        } catch (Exception e) {
            // Code graph may fail on small/empty projects, continue without it
        }
        return new ArchitectureReviewAgent(chatModel, registry.getAllTools(), strategy);
    }

    /**
     * 带配置和策略的工厂方法。
     */
    public static ArchitectureReviewAgent create(ChatModel chatModel, Path projectDir, ReviewConfig config, ReviewStrategy strategy) {
        ToolRegistry registry = ToolRegistry.createStandardTools(projectDir);
        try {
            GetCallGraphTool callGraphTool = new GetCallGraphTool(projectDir);
            registry.register(callGraphTool);
            registry.register(new GetRelatedFilesTool(callGraphTool));
        } catch (Exception e) {
            // Code graph may fail on small/empty projects, continue without it
        }
        try {
            registry.register(new SemanticSearchTool(projectDir, config));
        } catch (Exception e) {
            log.debug("Semantic search tool 初始化跳过: {}", e.getMessage());
        }
        return new ArchitectureReviewAgent(chatModel, registry.getAllTools(), strategy);
    }

    public AgentResponse review(AgentContext context) {
        return agent.run(context);
    }
}
