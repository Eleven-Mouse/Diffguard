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
 * 安全审查 Agent。
 * <p>
 * 专注于发现代码中的安全漏洞：
 * SQL 注入、命令注入、XSS、不安全的反序列化、
 * 硬编码密钥、路径遍历、SSRF、认证授权缺陷。
 * <p>
 * 使用 LangChain4j Function Calling 调用工具获取代码上下文。
 */
public class SecurityReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(SecurityReviewAgent.class);

    private static final String SYSTEM_PROMPT = PromptLoader.load(
            "/prompt-templates/reviewagents/security-system.txt",
            "你是一个安全审查专家 Agent。专注于发现代码变更中的安全漏洞。"
    );

    private final ReActAgent agent;

    public SecurityReviewAgent(ChatModel chatModel, List<AgentTool> tools) {
        this.agent = new ReActAgent(chatModel, tools, SYSTEM_PROMPT, 8);
    }

    /**
     * 带审查策略的构造方法，注入策略中的重点领域和额外规则。
     */
    public SecurityReviewAgent(ChatModel chatModel, List<AgentTool> tools, ReviewStrategy strategy) {
        this.agent = new ReActAgent(chatModel, tools, buildPromptWithStrategy(SYSTEM_PROMPT, strategy), 8);
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

    /**
     * 根据配置创建安全审查 Agent，支持配置化的 Embedding 提供者。
     */
    public static SecurityReviewAgent create(ChatModel chatModel, Path projectDir, ReviewConfig config) {
        ToolRegistry registry = ToolRegistry.createStandardTools(projectDir);
        try {
            registry.register(new SemanticSearchTool(projectDir, config));
        } catch (Exception e) {
            log.debug("Semantic search tool 初始化跳过: {}", e.getMessage());
        }
        return new SecurityReviewAgent(chatModel, registry.getAllTools());
    }

    /**
     * 带策略的工厂方法。
     */
    public static SecurityReviewAgent create(ChatModel chatModel, Path projectDir, ReviewStrategy strategy) {
        ToolRegistry registry = ToolRegistry.createStandardTools(projectDir);
        return new SecurityReviewAgent(chatModel, registry.getAllTools(), strategy);
    }

    /**
     * 带配置和策略的工厂方法。
     */
    public static SecurityReviewAgent create(ChatModel chatModel, Path projectDir, ReviewConfig config, ReviewStrategy strategy) {
        ToolRegistry registry = ToolRegistry.createStandardTools(projectDir);
        try {
            registry.register(new SemanticSearchTool(projectDir, config));
        } catch (Exception e) {
            log.debug("Semantic search tool 初始化跳过: {}", e.getMessage());
        }
        return new SecurityReviewAgent(chatModel, registry.getAllTools(), strategy);
    }

    public AgentResponse review(AgentContext context) {
        return agent.run(context);
    }

    /**
     * 将策略的重点领域和额外规则追加到系统 prompt 中。
     */
    static String buildPromptWithStrategy(String basePrompt, ReviewStrategy strategy) {
        if (strategy == null) return basePrompt;
        StringBuilder sb = new StringBuilder(basePrompt);
        if (strategy.getFocusAreas() != null && !strategy.getFocusAreas().isEmpty()) {
            sb.append("\n\n## 本次审查重点关注\n");
            for (String area : strategy.getFocusAreas()) {
                sb.append("- ").append(area).append("\n");
            }
        }
        if (strategy.getAdditionalRules() != null && !strategy.getAdditionalRules().isEmpty()) {
            sb.append("\n## 本次审查额外规则\n");
            for (String rule : strategy.getAdditionalRules()) {
                sb.append("- ").append(rule).append("\n");
            }
        }
        return sb.toString();
    }
}
