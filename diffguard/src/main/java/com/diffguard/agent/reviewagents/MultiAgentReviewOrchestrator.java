package com.diffguard.agent.reviewagents;

import com.diffguard.agent.core.*;
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
 * 使用 LangChain4j Function Calling 进行工具调用。
 */
public class MultiAgentReviewOrchestrator implements com.diffguard.review.ReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentReviewOrchestrator.class);

    private final ChatModel chatModel;
    private final Path projectDir;
    private final ExecutorService executor;
    private final int timeoutMinutes;

    public MultiAgentReviewOrchestrator(ChatModel chatModel, Path projectDir) {
        this(chatModel, projectDir, 3);
    }

    public MultiAgentReviewOrchestrator(ChatModel chatModel, Path projectDir,
                                         int timeoutMinutes) {
        this.chatModel = chatModel;
        this.projectDir = projectDir;
        this.executor = Executors.newFixedThreadPool(3);
        this.timeoutMinutes = timeoutMinutes;
    }

    /**
     * {@link com.diffguard.review.ReviewEngine} 统一入口。
     */
    @Override
    public ReviewResult review(List<DiffFileEntry> diffEntries, java.nio.file.Path projectDir) {
        return doReview(diffEntries, projectDir != null ? projectDir : this.projectDir);
    }

    /**
     * 执行多 Agent 并行审查。
     */
    public ReviewResult review(List<DiffFileEntry> diffEntries) {
        return doReview(diffEntries, projectDir);
    }

    private ReviewResult doReview(List<DiffFileEntry> diffEntries, Path effectiveProjectDir) {
        long startTime = System.currentTimeMillis();
        AgentContext context = new AgentContext(effectiveProjectDir, diffEntries, 15);

        List<NamedAgent> agents = createAgents(effectiveProjectDir);

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

        ReviewResult result = aggregateResults(responses, diffEntries);
        result.setReviewDurationMs(System.currentTimeMillis() - startTime);
        result.setTotalFilesReviewed(diffEntries.size());
        return result;
    }

    protected List<NamedAgent> createAgents(Path agentProjectDir) {
        SecurityReviewAgent security = SecurityReviewAgent.create(chatModel, agentProjectDir);
        PerformanceReviewAgent performance = PerformanceReviewAgent.create(chatModel, agentProjectDir);
        ArchitectureReviewAgent architecture = ArchitectureReviewAgent.create(chatModel, agentProjectDir);
        return List.of(
                new NamedAgent("Security", security::review),
                new NamedAgent("Performance", performance::review),
                new NamedAgent("Architecture", architecture::review)
        );
    }

    private ReviewResult aggregateResults(Map<String, AgentResponse> responses,
                                            List<DiffFileEntry> diffEntries) {
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

        log.info("多 Agent 审查聚合完成: {} issues ({} critical), {} agents completed",
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
