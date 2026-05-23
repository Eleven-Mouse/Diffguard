package com.diffguard.review;

import com.diffguard.agent.python.PythonReviewEngine;
import com.diffguard.review.ReviewEngine;
import com.diffguard.review.ReviewService;
import com.diffguard.platform.config.ReviewConfig;
import com.diffguard.review.model.DiffFileEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

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

    static String resolveToolServerUrl(ReviewConfig config) {
        String explicitUrl = System.getenv("DIFFGUARD_TOOL_SERVER_URL");
        if (explicitUrl != null && !explicitUrl.isBlank()) {
            return normalizeUrl(explicitUrl);
        }

        if (config.getToolService() != null && config.getToolService().getUrl() != null
                && !config.getToolService().getUrl().isBlank()) {
            return normalizeUrl(config.getToolService().getUrl());
        }

        String host = System.getenv("DIFFGUARD_TOOL_SERVER_HOST");
        if (host == null || host.isBlank()) {
            if (config.getToolService() != null && config.getToolService().getHost() != null
                    && !config.getToolService().getHost().isBlank()) {
                host = config.getToolService().getHost();
            } else {
                host = "localhost";
            }
        }
        int port;
        String envPort = System.getenv("DIFFGUARD_TOOL_SERVER_PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort.trim());
        } else if (config.getAgentService() != null) {
            // Backward compatibility with existing agent_service.tool_server_port
            port = config.getAgentService().getToolServerPort();
        } else if (config.getToolService() != null) {
            port = config.getToolService().getPort();
        } else {
            port = 9090;
        }
        return "http://" + host + ":" + port;
    }

    private static String normalizeUrl(String rawUrl) {
        String url = rawUrl.trim();
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
