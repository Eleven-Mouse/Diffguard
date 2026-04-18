package com.diffguard.cli;

import com.diffguard.config.ConfigLoader;
import com.diffguard.config.ReviewConfig;
import com.diffguard.git.DiffCollector;
import com.diffguard.exception.ConfigException;
import com.diffguard.exception.DiffCollectionException;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.exception.LlmApiException;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.ReviewResult;
import com.diffguard.output.ConsoleFormatter;
import com.diffguard.output.ProgressDisplay;
import com.diffguard.output.TerminalUI;
import com.diffguard.review.ReviewEngine;
import com.diffguard.review.ReviewEngineFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(name = "review", description = "使用AI审查代码变更")
public class ReviewCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ReviewCommand.class);

    @CommandLine.Option(names = "--staged", description = "审查暂存区的变更（git diff --cached）")
    boolean staged;

    @CommandLine.Option(names = "--from", description = "用于差异对比的源引用")
    String fromRef;

    @CommandLine.Option(names = "--to", description = "用于差异对比的目标引用")
    String toRef;

    @CommandLine.Option(names = "--force", description = "跳过审查（忽略严重问题）")
    boolean force;

    @CommandLine.Option(names = "--config", description = "配置文件路径")
    Path configPath;

    @CommandLine.Option(names = "--no-cache", description = "禁用结果缓存")
    boolean noCache;

    @CommandLine.Option(names = "--pipeline", description = "启用多阶段审查 Pipeline（安全/逻辑/质量 专项并行审查）")
    boolean pipeline;

    @CommandLine.Option(names = "--multi-agent", description = "启用多 Agent 并行审查（安全/性能/架构 Agent 并行）")
    boolean multiAgent;

    @CommandLine.ParentCommand
    DiffGuardMain parent;

    @Override
    public void run() {
        parent.setExitCode(doReview());
    }

    private int doReview() {
        Path projectDir = Path.of("").toAbsolutePath();

        ProgressDisplay.printBanner();

        // 1. 加载配置
        ReviewConfig config;
        try {
            config = configPath != null
                    ? ConfigLoader.loadFromFile(configPath)
                    : ConfigLoader.load(projectDir);
        } catch (ConfigException e) {
            TerminalUI.error("  Config load failed: " + e.getMessage());
            log.error("配置加载失败", e);
            return 1;
        } catch (IllegalArgumentException e) {
            TerminalUI.error("  Config load failed: " + e.getMessage());
            log.error("配置参数错误", e);
            return 1;
        }

        // 2. 收集差异
        ProgressDisplay.printCollectingDiffs();

        List<DiffFileEntry> diffEntries;
        try {
            if (staged) {
                diffEntries = DiffCollector.collectStagedDiff(projectDir, config);
            } else if (fromRef != null && toRef != null) {
                diffEntries = DiffCollector.collectDiffBetweenRefs(projectDir, fromRef, toRef, config);
            } else {
                TerminalUI.error("  Error: specify --staged or --from/--to");
                return 1;
            }
        } catch (DiffCollectionException e) {
            TerminalUI.error("  Diff collection failed: " + e.getMessage());
            log.error("差异收集失败", e);
            return 1;
        }

        if (diffEntries.isEmpty()) {
            ProgressDisplay.printNoChanges();
            return 0;
        }

        int totalLines = diffEntries.stream().mapToInt(DiffFileEntry::getLineCount).sum();
        ProgressDisplay.printDiffCollected(diffEntries.size(), totalLines);

        // AST enrichment (sidecar)
        diffEntries = new com.diffguard.ast.ASTEnricher(projectDir, config).enrich(diffEntries);

        // 3. 执行审查
        ReviewEngineFactory.EngineType engineType =
                ReviewEngineFactory.resolveEngineType(config, pipeline, multiAgent);
        ReviewResult result;
        try (ReviewEngine engine = ReviewEngineFactory.create(engineType, config, projectDir, diffEntries, noCache)) {
            if (engineType == ReviewEngineFactory.EngineType.PIPELINE) {
                TerminalUI.println("  " + "Using multi-stage pipeline (security/logic/quality)...");
            } else if (engineType == ReviewEngineFactory.EngineType.MULTI_AGENT) {
                TerminalUI.println("  " + "Using multi-agent parallel review...");
            }
            result = engine.review(diffEntries, projectDir);
        } catch (LlmApiException e) {
            TerminalUI.error("  AI review failed: " + e.getMessage());
            TerminalUI.error("  Commit aborted for safety. Use --force to bypass.");
            log.error("LLM 调用失败，状态码：{}", e.getStatusCode(), e);
            return 1;
        } catch (DiffGuardException e) {
            TerminalUI.error("  Review error: " + e.getMessage());
            log.error("审查过程出错", e);
            return 1;
        }

        // 4. 输出结果
        ConsoleFormatter.printReport(result);

        // 5. 退出码：发现严重问题且未强制跳过时返回1
        if (result.hasCriticalIssues() && !force) {
            return 1;
        }

        return 0;
    }
}
