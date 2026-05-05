package com.diffguard.service;

import com.diffguard.domain.agent.python.PythonReviewEngine;
import com.diffguard.domain.review.AsyncReviewEngine;
import com.diffguard.domain.review.ReviewEngine;
import com.diffguard.domain.review.ReviewService;
import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.infrastructure.messaging.RabbitMQConfig;
import com.diffguard.infrastructure.messaging.ReviewTaskMessage;
import com.diffguard.infrastructure.messaging.ReviewTaskPublisher;
import com.diffguard.domain.review.model.DiffFileEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 审查引擎工厂。
 * <p>
 * 根据配置和 CLI 标志创建对应的 {@link ReviewEngine} 实例。
 * SIMPLE 模式使用 Java 本地实现，PIPELINE 和 MULTI_AGENT 委托给 Python Agent 服务。
 * 支持 RabbitMQ 异步模式：任务发送到消息队列，由 Python Consumer 消费。
 */
public class ReviewEngineFactory {

    private static final Logger log = LoggerFactory.getLogger(ReviewEngineFactory.class);

    public enum EngineType {
        SIMPLE,
        PIPELINE,
        MULTI_AGENT
    }

    /**
     * 根据配置和标志解析引擎类型。
     */
    public static EngineType resolveEngineType(ReviewConfig config, boolean pipelineFlag, boolean multiAgentFlag) {
        if (multiAgentFlag) return EngineType.MULTI_AGENT;
        if (pipelineFlag || config.getPipeline().isEnabled()) return EngineType.PIPELINE;
        return EngineType.SIMPLE;
    }

    /**
     * 创建审查引擎实例（同步模式，保留向后兼容）。
     */
    public static ReviewEngine create(EngineType type, ReviewConfig config,
                                       Path projectDir, List<DiffFileEntry> diffEntries,
                                       boolean noCache) {
        return switch (type) {
            case SIMPLE -> new ReviewService(config, projectDir, noCache);
            case PIPELINE -> {
                String toolServerUrl = resolveToolServerUrl(config);
                log.info("Using Python Agent service for PIPELINE mode (tool server: {})", toolServerUrl);
                yield new PythonReviewEngine("PIPELINE", config, toolServerUrl);
            }
            case MULTI_AGENT -> {
                String toolServerUrl = resolveToolServerUrl(config);
                log.info("Using Python Agent service for MULTI_AGENT mode (tool server: {})", toolServerUrl);
                yield new PythonReviewEngine("MULTI_AGENT", config, toolServerUrl);
            }
        };
    }

    /**
     * 异步模式：通过 RabbitMQ 发送任务，返回 CompletableFuture。
     * 如果 RabbitMQ 不可用，自动降级到同步模式。
     */
    public static CompletableFuture<ReviewEngine> createAsync(
            EngineType type, ReviewConfig config,
            Path projectDir, List<DiffFileEntry> diffEntries,
            boolean noCache) {

        ReviewConfig.MessageQueueConfig mqConfig = config.getMessageQueue();
        if (mqConfig == null || !mqConfig.isEnabled()) {
            // 没有启用消息队列，降级到同步
            log.info("Message queue not enabled, falling back to sync mode");
            return CompletableFuture.completedFuture(
                    create(type, config, projectDir, diffEntries, noCache));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                RabbitMQConfig rabbitMQ = RabbitMQConfig.fromEnv();
                ReviewTaskPublisher publisher = new ReviewTaskPublisher(rabbitMQ);
                String mode = switch (type) {
                    case PIPELINE -> "PIPELINE";
                    case MULTI_AGENT -> "MULTI_AGENT";
                    case SIMPLE -> "SIMPLE";
                };
                String toolServerUrl = resolveToolServerUrl(config);
                ReviewTaskMessage message = new ReviewTaskMessage(
                        mode, config, diffEntries,
                        projectDir.toString(), toolServerUrl, false);
                String taskId = publisher.publish(message);
                log.info("Async review task submitted: id={}, mode={}", taskId, mode);
                return new AsyncReviewEngine(taskId, config);
            } catch (Exception e) {
                log.warn("RabbitMQ unavailable, falling back to sync: {}", e.getMessage());
                return create(type, config, projectDir, diffEntries, noCache);
            }
        });
    }

    static String resolveToolServerUrl(ReviewConfig config) {
        String host = System.getenv("DIFFGUARD_TOOL_SERVER_HOST");
        if (host == null || host.isBlank()) {
            host = "localhost";
        }
        int port = 9090;
        if (config.getAgentService() != null) {
            port = config.getAgentService().getToolServerPort();
        }
        return "http://" + host + ":" + port;
    }
}
