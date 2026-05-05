package com.diffguard.cli;

import com.diffguard.service.ReviewApplicationService;
import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.domain.review.model.DiffFileEntry;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.infrastructure.output.ReviewReportPrinter;
import com.diffguard.infrastructure.output.ProgressDisplay;
import com.diffguard.infrastructure.output.TerminalUI;
import com.diffguard.service.ReviewEngineFactory;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(name = "review", description = "使用AI审查代码变更")
public class ReviewCommand implements Runnable {

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
        ReviewApplicationService service = new ReviewApplicationService();

        ProgressDisplay.printBanner();

        // 1. 加载配置
        ReviewConfig config = service.loadConfig(projectDir, configPath);
        if (config == null) {
            TerminalUI.error("  Config load failed");
            return 1;
        }

        // 2. 收集差异 + AST 增强
        ProgressDisplay.printCollectingDiffs();
        List<DiffFileEntry> diffEntries = service.collectAndEnrich(projectDir, config, staged, fromRef, toRef);
        if (diffEntries == null) {
            TerminalUI.error("  Diff collection failed");
            return 1;
        }

        if (diffEntries.isEmpty()) {
            ProgressDisplay.printNoChanges();
            return 0;
        }

        int totalLines = diffEntries.stream().mapToInt(DiffFileEntry::getLineCount).sum();
        ProgressDisplay.printDiffCollected(diffEntries.size(), totalLines);

        // 3. 提示引擎模式
        ReviewEngineFactory.EngineType engineType =
                ReviewEngineFactory.resolveEngineType(config, pipeline, multiAgent);
        if (engineType == ReviewEngineFactory.EngineType.PIPELINE) {
            TerminalUI.println("  Using multi-stage pipeline (security/logic/quality)...");
        } else if (engineType == ReviewEngineFactory.EngineType.MULTI_AGENT) {
            TerminalUI.println("  Using multi-agent parallel review...");
        }

        // 4. 执行审查
        ReviewResult result = service.review(projectDir, config, diffEntries, noCache, pipeline, multiAgent);
        if (result == null) {
            TerminalUI.error("  Review failed. Commit aborted for safety. Use --force to bypass.");
            return 1;
        }

        // 5. 输出结果
        ReviewReportPrinter.printReport(result);

        return (result.hasCriticalIssues() && !force) ? 1 : 0;
    }
}
