package com.diffguard.review;

import com.diffguard.review.ast.ASTEnricher;
import com.diffguard.review.ReviewEngine;
import com.diffguard.platform.config.ConfigLoader;
import com.diffguard.platform.config.ReviewConfig;
import com.diffguard.platform.git.GitHubPrDiffCollector;
import com.diffguard.exception.ConfigException;
import com.diffguard.exception.DiffCollectionException;
import com.diffguard.review.model.DiffFileEntry;
import com.diffguard.review.model.ReviewResult;
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
    public List<DiffFileEntry> collectAndEnrich(Path projectDir, ReviewConfig config, String prSpec) {
        List<DiffFileEntry> diffEntries = collectDiff(projectDir, config, prSpec);
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
        return new ReviewExecutionAdapter(config).review(projectDir, diffEntries, engineType, noCache);
    }

    private List<DiffFileEntry> collectDiff(Path projectDir, ReviewConfig config, String prSpec) {
        try {
            if (prSpec == null || prSpec.isBlank()) {
                log.error("未指定 PR：需要 --pr owner/repo#number");
                return null;
            }
            return GitHubPrDiffCollector.collectPrDiff(projectDir, prSpec, config);
        } catch (DiffCollectionException e) {
            log.error("差异收集失败", e);
            return null;
        }
    }
}
