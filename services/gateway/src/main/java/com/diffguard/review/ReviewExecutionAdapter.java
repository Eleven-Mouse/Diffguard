package com.diffguard.review;

import com.diffguard.review.ReviewEngine;
import com.diffguard.review.model.DiffFileEntry;
import com.diffguard.review.model.ReviewResult;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.exception.LlmApiException;
import com.diffguard.platform.config.ReviewConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Gateway 侧统一审查调用适配层：
 * - remote: 通过 Orchestrator HTTP 契约调用独立编排服务
 * - legacy: 回退本地 ReviewEngine 执行
 */
public class ReviewExecutionAdapter {

    private static final Logger log = LoggerFactory.getLogger(ReviewExecutionAdapter.class);

    private final ReviewConfig config;

    public ReviewExecutionAdapter(ReviewConfig config) {
        this.config = config;
    }

    public ReviewResult review(Path projectDir,
                               List<DiffFileEntry> diffEntries,
                               ReviewEngineFactory.EngineType engineType,
                               boolean noCache) {
        if (isRemoteMode()) {
            try {
                log.info("Using remote orchestrator mode: {}", config.getOrchestrator().resolveUrl());
                return new OrchestratorClient(config).review(projectDir, diffEntries, engineType);
            } catch (DiffGuardException e) {
                if (!isFallbackToLegacy()) {
                    System.err.println("  Orchestrator 调用失败：" + e.getMessage());
                    return null;
                }
                log.warn("Remote orchestrator failed, fallback to legacy local engine: {}", e.getMessage());
            }
        }
        return reviewLegacy(projectDir, diffEntries, engineType, noCache);
    }

    private ReviewResult reviewLegacy(Path projectDir,
                                      List<DiffFileEntry> diffEntries,
                                      ReviewEngineFactory.EngineType engineType,
                                      boolean noCache) {
        try (ReviewEngine engine = ReviewEngineFactory.create(
                engineType, config, projectDir, diffEntries, noCache)) {
            return engine.review(diffEntries, projectDir);
        } catch (LlmApiException e) {
            System.err.println("  LLM 调用失败（状态码 " + e.getStatusCode() + "）：" + e.getMessage());
            return null;
        } catch (DiffGuardException e) {
            System.err.println("  审查过程出错：" + e.getMessage());
            return null;
        } catch (Exception e) {
            System.err.println("  未预期的错误：" + e.getMessage());
            e.printStackTrace(System.err);
            return null;
        }
    }

    private boolean isRemoteMode() {
        return config.getOrchestrator() != null && config.getOrchestrator().isRemoteMode();
    }

    private boolean isFallbackToLegacy() {
        return config.getOrchestrator() != null && config.getOrchestrator().isFallbackToLegacy();
    }
}

