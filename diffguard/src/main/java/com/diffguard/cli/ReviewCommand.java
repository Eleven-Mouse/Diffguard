package com.diffguard.cli;

import com.diffguard.config.ConfigLoader;
import com.diffguard.config.ReviewConfig;
import com.diffguard.git.DiffCollector;
import com.diffguard.exception.ConfigException;
import com.diffguard.exception.DiffCollectionException;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.exception.LlmApiException;
import com.diffguard.agent.pipeline.MultiStageReviewService;
import com.diffguard.llm.provider.LangChain4jClaudeAdapter;
import com.diffguard.llm.provider.LangChain4jOpenAiAdapter;
import com.diffguard.llm.tools.FileAccessSandbox;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.ReviewResult;
import com.diffguard.output.ConsoleFormatter;
import com.diffguard.output.ProgressDisplay;
import com.diffguard.review.ReviewService;
import dev.langchain4j.model.chat.ChatModel;
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
            System.err.println("  配置加载失败：" + e.getMessage());
            log.error("配置加载失败", e);
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("  配置加载失败：" + e.getMessage());
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
                System.err.println("  错误：请指定 --staged 或 --from/--to 引用");
                return 1;
            }
        } catch (DiffCollectionException e) {
            System.err.println("  差异收集失败：" + e.getMessage());
            log.error("差异收集失败", e);
            return 1;
        }

        if (diffEntries.isEmpty()) {
            ProgressDisplay.printNoChanges();
            return 0;
        }

        int totalLines = diffEntries.stream().mapToInt(DiffFileEntry::getLineCount).sum();
        ProgressDisplay.printDiffCollected(diffEntries.size(), totalLines);

        // 3. 执行审查
        boolean usePipeline = pipeline || config.getPipeline().isEnabled();
        ReviewResult result;
        try {
            if (usePipeline) {
                result = runPipelineReview(config, diffEntries, projectDir);
            } else {
                try (ReviewService reviewService = new ReviewService(config, projectDir, noCache)) {
                    result = reviewService.review(diffEntries);
                }
            }
        } catch (LlmApiException e) {
            // LLM 调用失败，fail-closed：阻止提交
            System.err.println("  AI 审查失败：" + e.getMessage());
            System.err.println("  为确保代码安全，提交已中止。使用 --force 可跳过。");
            log.error("LLM 调用失败，状态码：{}", e.getStatusCode(), e);
            return 1;
        } catch (DiffGuardException e) {
            System.err.println("  审查过程出错：" + e.getMessage());
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

    /**
     * 执行多阶段 Pipeline 审查。
     */
    private ReviewResult runPipelineReview(ReviewConfig config,
                                           List<DiffFileEntry> diffEntries,
                                           java.nio.file.Path projectDir) throws LlmApiException {
        ChatModel chatModel = createChatModel(config, null);

        // 创建带 Tool Use 的 Pipeline（传入文件沙箱）
        java.util.Set<String> filePaths = diffEntries.stream()
                .map(DiffFileEntry::getFilePath)
                .collect(java.util.stream.Collectors.toSet());
        FileAccessSandbox sandbox = new FileAccessSandbox(projectDir, filePaths);

        try (MultiStageReviewService pipeline = new MultiStageReviewService(chatModel, sandbox)) {
            System.out.println("  使用多阶段审查 Pipeline（安全/逻辑/质量 专项并行审查）...");
            ReviewResult result = pipeline.review(diffEntries, projectDir,
                    config.getPipeline().getMaxTotalTokens(), config.getLlm().getProvider());
            result.setTotalTokensUsed(pipeline.getTotalTokensUsed());
            return result;
        }
    }

    private static ChatModel createChatModel(ReviewConfig config,
                                              com.diffguard.llm.provider.TokenTracker tracker) {
        String providerName = config.getLlm().getProvider().toLowerCase();
        if ("claude".equals(providerName)) {
            return new LangChain4jClaudeAdapter(config.getLlm(), tracker != null ? tracker : tokens -> {}).getChatModel();
        } else {
            return new LangChain4jOpenAiAdapter(config.getLlm(), tracker != null ? tracker : tokens -> {}).getChatModel();
        }
    }
}
