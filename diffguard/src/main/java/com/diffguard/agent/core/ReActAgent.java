package com.diffguard.agent.core;

import com.diffguard.agent.tools.AgentFunctionToolProvider;
import com.diffguard.model.IssueRecord;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.Severity;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * ReAct (Reasoning + Acting) Agent 引擎。
 * <p>
 * 使用 LangChain4j Function Calling 代替正则解析：
 * <ul>
 *   <li>LLM 通过原生 Function Calling 调用 {@code @Tool} 方法获取代码上下文</li>
 *   <li>LangChain4j 自动管理 Thought → Action → Observation 循环</li>
 *   <li>最终结果通过结构化输出 ({@link ReActReviewOutput}) 返回</li>
 * </ul>
 * <p>
 * 每次调用 {@link #run(AgentContext)} 时，创建新的 AiServices 代理和工具提供者，
 * 以绑定到当前审查会话的上下文。
 */
public class ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    private final Map<String, AgentTool> tools;
    private final String systemPrompt;
    private final Function<AgentFunctionToolProvider, ReActAgentService> serviceFactory;

    /**
     * 生产构造方法：使用 ChatModel 创建 AiServices 代理。
     */
    public ReActAgent(ChatModel chatModel, List<AgentTool> tools,
                      String systemPrompt, int maxIterations) {
        this.tools = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            this.tools.put(tool.name(), tool);
        }
        this.systemPrompt = systemPrompt;
        this.serviceFactory = tp -> AiServices.builder(ReActAgentService.class)
                .chatModel(chatModel)
                .tools(tp)
                .build();
    }

    /**
     * 测试构造方法：注入自定义 service 工厂。
     */
    public ReActAgent(List<AgentTool> tools, String systemPrompt, int maxIterations,
               Function<AgentFunctionToolProvider, ReActAgentService> serviceFactory) {
        this.tools = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            this.tools.put(tool.name(), tool);
        }
        this.systemPrompt = systemPrompt;
        this.serviceFactory = serviceFactory;
    }

    /**
     * 执行 Agent 推理循环。
     * <p>
     * 创建 AiServices 代理并调用 LLM。LangChain4j 自动处理 Function Calling 循环：
     * LLM 调用工具 → 工具返回结果 → LLM 继续分析 → 直到给出最终审查结果。
     *
     * @param context Agent 上下文（包含 diff、项目目录等）
     * @return Agent 响应
     */
    public AgentResponse run(AgentContext context) {
        List<StepRecord> trace = new ArrayList<>();

        AgentFunctionToolProvider toolProvider = new AgentFunctionToolProvider(
                List.copyOf(tools.values()), context, trace);

        ReActAgentService service = serviceFactory.apply(toolProvider);

        try {
            Result<ReActReviewOutput> result = service.review(systemPrompt, context.getCombinedDiff());

            // 跟踪 token 用量
            if (result.tokenUsage() != null) {
                long tokens = result.tokenUsage().inputTokenCount() + result.tokenUsage().outputTokenCount();
                if (tokens > 0) {
                    context.addTokens((int) tokens);
                }
            }

            ReActReviewOutput output = result.content();
            if (output != null) {
                trace.add(StepRecord.finalAnswer(output.summary() != null ? output.summary() : "审查完成"));
                context.recordStep(StepRecord.finalAnswer("审查完成"));
                return convertOutput(output, trace, context, toolProvider.getCallCount());
            } else {
                return AgentResponse.builder()
                        .completed(false)
                        .summary("Agent 返回空结果")
                        .reasoningTrace(trace)
                        .toolCallsMade(toolProvider.getCallCount())
                        .totalTokensUsed(context.getTotalTokensUsed())
                        .build();
            }
        } catch (Exception e) {
            log.warn("Agent 执行失败: {}", e.getMessage());
            return AgentResponse.builder()
                    .completed(false)
                    .summary("Agent 执行失败: " + e.getMessage())
                    .reasoningTrace(trace)
                    .toolCallsMade(toolProvider.getCallCount())
                    .totalTokensUsed(context.getTotalTokensUsed())
                    .build();
        }
    }

    /**
     * 将结构化输出转换为 AgentResponse。
     */
    AgentResponse convertOutput(ReActReviewOutput output, List<StepRecord> trace,
                                AgentContext context, int toolCallsMade) {
        AgentResponse.Builder builder = AgentResponse.builder()
                .reasoningTrace(trace)
                .toolCallsMade(toolCallsMade)
                .totalTokensUsed(context.getTotalTokensUsed())
                .completed(true);

        builder.hasCritical(Boolean.TRUE.equals(output.has_critical()));
        builder.summary(output.summary() != null ? output.summary() : "");

        if (output.issues() != null) {
            for (IssueRecord ir : output.issues()) {
                ReviewIssue issue = new ReviewIssue();
                issue.setSeverity(Severity.fromString(ir.severity()));
                issue.setFile(ir.file() != null ? ir.file() : "");
                issue.setLine(ir.line());
                issue.setType(ir.type() != null ? ir.type() : "");
                issue.setMessage(ir.message() != null ? ir.message() : "");
                issue.setSuggestion(ir.suggestion() != null ? ir.suggestion() : "");
                builder.issue(issue);
            }
        }

        if (output.highlights() != null) {
            for (String h : output.highlights()) {
                builder.highlight(h);
            }
        }

        if (output.test_suggestions() != null) {
            for (String t : output.test_suggestions()) {
                builder.testSuggestion(t);
            }
        }

        return builder.build();
    }
}
