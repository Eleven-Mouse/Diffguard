package com.diffguard.agent.reviewagents;

import com.diffguard.agent.core.*;
import com.diffguard.agent.strategy.ReviewStrategy;
import com.diffguard.agent.strategy.ReviewStrategy.AgentType;
import com.diffguard.agent.strategy.StrategyPlanner;
import com.diffguard.config.ReviewConfig;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 多 Agent Review 编排器。
 * <p>
 * 并行调度多个专用 Agent（安全、性能、架构），
 * 聚合结果、去重、定级，输出统一的 ReviewResult。
 * <p>
 * 通过 {@link StrategyPlanner} 根据代码变更画像动态调整 Agent 权重，
 * 跳过权重为 0 的 Agent，并注入针对性的审查规则和重点领域。
 */
public class MultiAgentReviewOrchestrator implements com.diffguard.review.ReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentReviewOrchestrator.class);

    private final ChatModel chatModel;
    private final Path projectDir;
    private final ExecutorService executor;
    private final int timeoutMinutes;
    private final ReviewConfig config;

    public MultiAgentReviewOrchestrator(ChatModel chatModel, Path projectDir) {
        this(chatModel, projectDir, 3, null);
    }

    public MultiAgentReviewOrchestrator(ChatModel chatModel, Path projectDir,
                                         int timeoutMinutes) {
        this(chatModel, projectDir, timeoutMinutes, null);
    }

    public MultiAgentReviewOrchestrator(ChatModel chatModel, Path projectDir,
                                         ReviewConfig config) {
        this(chatModel, projectDir, 3, config);
    }

    private MultiAgentReviewOrchestrator(ChatModel chatModel, Path projectDir,
                                          int timeoutMinutes, ReviewConfig config) {
        this.chatModel = chatModel;
        this.projectDir = projectDir;
        this.executor = Executors.newFixedThreadPool(3);
        this.timeoutMinutes = timeoutMinutes;
        this.config = config;
    }

    @Override
    public ReviewResult review(List<DiffFileEntry> diffEntries, java.nio.file.Path projectDir) {
        return doReview(diffEntries, projectDir != null ? projectDir : this.projectDir);
    }

    public ReviewResult review(List<DiffFileEntry> diffEntries) {
        return doReview(diffEntries, projectDir);
    }

    private ReviewResult doReview(List<DiffFileEntry> diffEntries, Path effectiveProjectDir) {
        long startTime = System.currentTimeMillis();

        // 基于代码变更画像生成审查策略
        ReviewStrategy strategy = StrategyPlanner.plan(diffEntries);
        log.info("审查策略: {} (权重: Security={}/Performance={}/Architecture={})",
                strategy.getName(),
                strategy.getAgentWeights().getOrDefault(AgentType.SECURITY, 0.0),
                strategy.getAgentWeights().getOrDefault(AgentType.PERFORMANCE, 0.0),
                strategy.getAgentWeights().getOrDefault(AgentType.ARCHITECTURE, 0.0));

        AgentContext context = new AgentContext(effectiveProjectDir, diffEntries, 15);

        List<NamedAgent> agents = createAgents(effectiveProjectDir, strategy);

        Map<String, Future<AgentResponse>> futures = new LinkedHashMap<>();
        for (NamedAgent na : agents) {
            futures.put(na.name(), executor.submit(() -> {
                try {
                    return na.agent().review(context);
                } catch (Exception e) {
                    log.warn("{} Agent 执行失败: {}", na.name(), e.getMessage());
                    return AgentResponse.builder()
                            .completed(false)
                            .summary(na.name() + " Agent 执行失败: " + e.getMessage())
                            .build();
                }
            }));
        }

        Map<String, AgentResponse> responses = collectResponses(futures);

        ReviewResult result = aggregateResults(responses, diffEntries, strategy);
        result.setReviewDurationMs(System.currentTimeMillis() - startTime);
        result.setTotalFilesReviewed(diffEntries.size());
        return result;
    }

    private Map<String, AgentResponse> collectResponses(Map<String, Future<AgentResponse>> futures) {
        Map<String, AgentResponse> responses = new LinkedHashMap<>();
        for (var entry : futures.entrySet()) {
            try {
                AgentResponse response = entry.getValue().get(timeoutMinutes, TimeUnit.MINUTES);
                responses.put(entry.getKey(), response);
                log.info("{} Agent 完成: {} issues, critical={}", entry.getKey(),
                        response.getIssues().size(), response.isHasCritical());
            } catch (TimeoutException e) {
                entry.getValue().cancel(true);
                log.warn("{} Agent 超时 ({}分钟)", entry.getKey(), timeoutMinutes);
                responses.put(entry.getKey(), AgentResponse.builder()
                        .completed(false)
                        .summary(entry.getKey() + " Agent 超时")
                        .build());
            } catch (Exception e) {
                log.warn("{} Agent 异常: {}", entry.getKey(), e.getMessage());
                responses.put(entry.getKey(), AgentResponse.builder()
                        .completed(false)
                        .summary(entry.getKey() + " Agent 异常")
                        .build());
            }
        }
        return responses;
    }

    /**
     * 根据审查策略创建 Agent 列表，跳过权重为 0 的 Agent。
     */
    protected List<NamedAgent> createAgents(Path agentProjectDir, ReviewStrategy strategy) {
        List<NamedAgent> agents = new ArrayList<>();

        double securityWeight = strategy.getAgentWeights().getOrDefault(AgentType.SECURITY, 1.0);
        double performanceWeight = strategy.getAgentWeights().getOrDefault(AgentType.PERFORMANCE, 1.0);
        double architectureWeight = strategy.getAgentWeights().getOrDefault(AgentType.ARCHITECTURE, 1.0);

        if (securityWeight > 0) {
            SecurityReviewAgent security = config != null
                    ? SecurityReviewAgent.create(chatModel, agentProjectDir, config, strategy)
                    : SecurityReviewAgent.create(chatModel, agentProjectDir, strategy);
            agents.add(new NamedAgent("Security", security::review));
        }
        if (performanceWeight > 0) {
            PerformanceReviewAgent performance = config != null
                    ? PerformanceReviewAgent.create(chatModel, agentProjectDir, config, strategy)
                    : PerformanceReviewAgent.create(chatModel, agentProjectDir, strategy);
            agents.add(new NamedAgent("Performance", performance::review));
        }
        if (architectureWeight > 0) {
            ArchitectureReviewAgent architecture = config != null
                    ? ArchitectureReviewAgent.create(chatModel, agentProjectDir, config, strategy)
                    : ArchitectureReviewAgent.create(chatModel, agentProjectDir, strategy);
            agents.add(new NamedAgent("Architecture", architecture::review));
        }

        log.info("启用 {} 个 Agent: {}", agents.size(),
                agents.stream().map(NamedAgent::name).collect(Collectors.joining(", ")));
        return agents;
    }

    /**
     * 向后兼容的无策略版本。
     */
    protected List<NamedAgent> createAgents(Path agentProjectDir) {
        return createAgents(agentProjectDir, ReviewStrategy.builder()
                .name("default")
                .agentWeight(AgentType.SECURITY, 1.0)
                .agentWeight(AgentType.PERFORMANCE, 1.0)
                .agentWeight(AgentType.ARCHITECTURE, 1.0)
                .build());
    }

    private ReviewResult aggregateResults(Map<String, AgentResponse> responses,
                                            List<DiffFileEntry> diffEntries,
                                            ReviewStrategy strategy) {
        ReviewResult result = new ReviewResult();
        List<AgentResponse> completedResponses = responses.values().stream()
                .filter(AgentResponse::isCompleted)
                .toList();

        Set<String> seenIssueKeys = new HashSet<>();
        for (AgentResponse response : completedResponses) {
            for (ReviewIssue issue : response.getIssues()) {
                String key = deduplicationKey(issue);
                if (seenIssueKeys.add(key)) {
                    result.addIssue(issue);
                }
            }
        }

        boolean anyCritical = completedResponses.stream()
                .anyMatch(AgentResponse::isHasCritical);
        if (anyCritical || result.getIssues().stream()
                .anyMatch(i -> i.getSeverity() == Severity.CRITICAL)) {
            result.setHasCriticalFlag(true);
        }

        if (!completedResponses.isEmpty() && result.getIssues().isEmpty()) {
            if (completedResponses.stream().allMatch(r -> r.getRawResponse() != null)) {
                result.setRawReport(completedResponses.stream()
                        .map(AgentResponse::getRawResponse)
                        .collect(Collectors.joining("\n\n")));
            }
        }

        log.info("多 Agent 审查聚合完成 [策略: {}]: {} issues ({} critical), {} agents completed",
                strategy.getName(),
                result.getIssues().size(),
                result.hasCriticalIssues() ? "有" : "无",
                completedResponses.size());

        return result;
    }

    private String deduplicationKey(ReviewIssue issue) {
        return issue.getFile() + ":" + issue.getLine() + ":" + issue.getType();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    protected record NamedAgent(String name, ReviewerAgent agent) {}

    @FunctionalInterface
    public interface ReviewerAgent {
        AgentResponse review(AgentContext context);
    }
}
