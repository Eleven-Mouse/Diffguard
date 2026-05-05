package com.diffguard.service;

import com.diffguard.domain.ast.ASTEnricher;
import com.diffguard.domain.review.ReviewEngine;
import com.diffguard.infrastructure.config.ConfigLoader;
import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.infrastructure.git.DiffCollector;
import com.diffguard.exception.ConfigException;
import com.diffguard.exception.DiffCollectionException;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.exception.LlmApiException;
import com.diffguard.domain.review.model.DiffFileEntry;
import com.diffguard.domain.review.model.ReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * 代码审查应用服务。
 * 封装：配置加载 → Diff 收集 → AST 增强 → 引擎审查 的完整管线。
 */
public class ReviewApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReviewApplicationService.class);

    /**
     * 加载审查配置。
     *
     * @return 配置对象，加载失败返回 null
     */
    public ReviewConfig loadConfig(Path projectDir, Path configPath) {
        try {
            return configPath != null
                    ? ConfigLoader.loadFromFile(configPath)
                    : ConfigLoader.load(projectDir);
        } catch (ConfigException e) {
            log.error("配置加载失败", e);
            return null;
        } catch (IllegalArgumentException e) {
            log.error("配置参数错误", e);
            return null;
        }
    }

    /**
     * 收集 diff 并执行 AST 增强。
     *
     * @return 差异条目列表，收集失败返回 null，无差异返回空列表
     */
    public List<DiffFileEntry> collectAndEnrich(Path projectDir, ReviewConfig config,
                                                 boolean staged, String fromRef, String toRef) {
        List<DiffFileEntry> diffEntries = collectDiff(projectDir, config, staged, fromRef, toRef);
        if (diffEntries == null) return null;

        if (diffEntries.isEmpty()) return diffEntries;

        return new ASTEnricher(projectDir, config).enrich(diffEntries);
    }

    /**
     * 执行代码审查。
     *
     * @return 审查结果，失败返回 null
     */
    public ReviewResult review(Path projectDir, ReviewConfig config,
                                List<DiffFileEntry> diffEntries,
                                boolean noCache, boolean pipeline, boolean multiAgent) {
        ReviewEngineFactory.EngineType engineType =
                ReviewEngineFactory.resolveEngineType(config, pipeline, multiAgent);

        try (ReviewEngine engine = ReviewEngineFactory.create(
                engineType, config, projectDir, diffEntries, noCache)) {
            return engine.review(diffEntries, projectDir);
        } catch (LlmApiException e) {
            log.error("LLM 调用失败，状态码：{}", e.getStatusCode(), e);
            return null;
        } catch (DiffGuardException e) {
            log.error("审查过程出错", e);
            return null;
        }
    }

    private List<DiffFileEntry> collectDiff(Path projectDir, ReviewConfig config,
                                             boolean staged, String fromRef, String toRef) {
        try {
            if (staged) {
                return DiffCollector.collectStagedDiff(projectDir, config);
            } else if (fromRef != null && toRef != null) {
                return DiffCollector.collectDiffBetweenRefs(projectDir, fromRef, toRef, config);
            } else {
                log.error("未指定差异模式：需要 --staged 或 --from/--to");
                return null;
            }
        } catch (DiffCollectionException e) {
            log.error("差异收集失败", e);
            return null;
        }
    }
}
